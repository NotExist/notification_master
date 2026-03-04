# 過濾規則引擎模組化重構規劃

## 動機

現行 `FilterRule` 將「目標匹配」和「處置行為」綁定在一起：
- 匹配條件僅限 `packageName` + `channelId`，缺乏內容層面（標題/文字關鍵字）的匹配能力
- 每新增一種處置行為（NOTIFICATION、CALENDAR_EXPORT、AUTO_DISMISS）就需要擴充 `FilterCategory` enum 並在多處新增 `when` 分支
- `dismissDelayMs` 這類處置專屬參數直接放在 `FilterRule` data class 中，其他 category 不使用卻無法排除
- Channel 查詢 API 整合後，需要以 channel 屬性（importance、group）作為匹配條件，現有架構無法表達

## 核心設計：Matcher + Action 分離

```
Rule {
    id, createdAt
    matchers: List<Matcher>    ← 目標條件（AND 組合）
    action: Action             ← 處置行為
}
```

### Matcher（目標決定）

可組合的匹配條件，一條規則內的多個 Matcher 以 AND 邏輯組合。

| Matcher 類型 | 匹配對象 | 現有對應 | 說明 |
|-------------|---------|---------|------|
| `PackageMatcher` | `packageName` | `FilterRule.packageName` | 精確匹配（必填） |
| `ChannelMatcher` | `channelId` | `FilterRule.channelId` | 精確匹配（選填，null = 所有 channel） |
| `EventTypeMatcher` | `EventType` | `FilterRule.eventTypes` | 匹配事件類型集合 |
| `KeywordMatcher` | title / text / bigText | 新增 | 子字串或 regex 匹配通知內容 |
| `ChannelPropertyMatcher` | importance, group 等 | 新增（需 Channel API） | 依 channel 屬性匹配 |

```kotlin
sealed interface Matcher {
    /** 判斷通知是否匹配此條件 */
    fun matches(context: MatchContext): Boolean

    data class Package(val packageName: String) : Matcher
    data class Channel(val channelId: String) : Matcher
    data class EventTypes(val types: Set<String>) : Matcher
    data class Keyword(
        val pattern: String,
        val fields: Set<KeywordField>,  // TITLE, TEXT, BIG_TEXT, SUB_TEXT
        val isRegex: Boolean = false
    ) : Matcher
    data class ChannelProperty(
        val minImportance: Int? = null,
        val groupId: String? = null
    ) : Matcher
}
```

`MatchContext` 封裝匹配時可取得的通知資訊：

```kotlin
data class MatchContext(
    val packageName: String,
    val channelId: String?,
    val eventType: EventType,
    // 以下為延遲取得（僅 KeywordMatcher 需要，避免無謂開銷）
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    // Channel 屬性（ChannelPropertyMatcher 需要）
    val channelImportance: Int? = null,
    val channelGroupId: String? = null
)
```

### Action（處置行為）

```kotlin
sealed interface RuleAction {
    /** 不記錄通知（現有 NOTIFICATION 黑名單） */
    data object SkipRecord : RuleAction

    /** 日曆匯出白名單（現有 CALENDAR_EXPORT） */
    data object CalendarExport : RuleAction

    /** 自動清除（現有 AUTO_DISMISS） */
    data class AutoDismiss(val delayMs: Long = 0) : RuleAction

    // 未來可擴充：
    // data class Forward(val target: ...) : RuleAction
    // data object HighlightInTimeline : RuleAction
}
```

### 完整 Rule

```kotlin
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val matchers: List<Matcher>,
    val action: RuleAction,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 所有 matcher 都通過才算匹配 */
    fun matches(context: MatchContext): Boolean =
        matchers.all { it.matches(context) }
}
```

---

## Channel 查詢 API 整合

### 現狀

- Channel 資訊儲存在 `ChannelEntity`（Room），由 `NotificationCaptureService.updateChannel()` 維護
- `ChannelDao.getByPackageName()` / `getByPackageAndChannelId()` 已有查詢
- `FilterRuleDialogHelper` 的 channel autocomplete 已從 DB 載入建議清單

### 補完項目

1. **ChannelPropertyMatcher**：匹配 channel importance / group / isBlocked
   - 查詢時機：Service 收到通知時，從 `Ranking.channel`（API 28+）或 DB 取得 channel 屬性
   - API 26-27：只能從 DB 取得（首次收到時尚無記錄，需 fallback）

2. **Dialog UI 擴充**：ChannelPropertyMatcher 的 UI 表示
   - importance 下拉選單（MIN/LOW/DEFAULT/HIGH/MAX）
   - channel group 自動完成（從 DB 載入）

3. **MatchContext 建構**：在 `processNotification()` 中建構完整的 `MatchContext`
   - 基礎欄位（package、channel、eventType）：從 `StatusBarNotification` 直接取得
   - 內容欄位（title、text）：從 `NotificationExtractor` 已提取的 entity 取得
   - Channel 屬性：從 `Ranking.channel` 或 DB 查詢

---

## 序列化方案

JSON 格式向後相容：

```json
{
  "version": 2,
  "exportTime": 1234567890,
  "rules": [
    {
      "id": "uuid",
      "matchers": [
        { "type": "Package", "packageName": "com.example" },
        { "type": "Channel", "channelId": "messages" },
        { "type": "Keyword", "pattern": "廣告", "fields": ["TITLE", "TEXT"], "isRegex": false }
      ],
      "action": { "type": "AutoDismiss", "delayMs": 300000 },
      "createdAt": 1234567890
    }
  ]
}
```

### 遷移策略

匯入 `version: 1` JSON 時自動轉換：
- `packageName` → `PackageMatcher`
- `channelId` → `ChannelMatcher`（非 null 時）
- `eventTypes` → `EventTypeMatcher`
- category + `dismissDelayMs` → 對應 `RuleAction`

SharedPreferences key 維持 `filter_rules_{ACTION_TYPE}` 或統一為 `filter_rules_v2`。

---

## 受影響的檔案與引用點

### Service 層（3 個呼叫點）

| 檔案 | 位置 | 現行呼叫 | 遷移方式 |
|------|------|---------|---------|
| `NotificationCaptureService` | `processNotification()` | `FilterRuleStore.matches(NOTIFICATION, ...)` | `RuleEngine.findAction(SkipRecord, matchContext)` |
| `NotificationCaptureService` | `processRemoval()` | `FilterRuleStore.matches(NOTIFICATION, ...)` | 同上 |
| `NotificationCaptureService` | `processRankingUpdate()` | `FilterRuleStore.matches(NOTIFICATION, ...)` | 同上 |
| `NotificationCaptureService` | `checkAutoDismiss()` | `FilterRuleStore.findMatchingRule(AUTO_DISMISS, ...)` | `RuleEngine.findMatchingRule(AutoDismiss, matchContext)` |

### 日曆匯出（1 個呼叫點）

| 檔案 | 位置 | 現行呼叫 | 遷移方式 |
|------|------|---------|---------|
| `SettingsFragment` | `exportToCalendar()` | `FilterRuleStore.matchesSource(CALENDAR_EXPORT, ...)` | `RuleEngine.findAction(CalendarExport, matchContext)` |

### UI 層（6+ 個影響點）

| 檔案 | 影響 |
|------|------|
| `FilterRuleDialogHelper` | 重寫 — matcher 列表 UI + action 選擇 + keyword 輸入 |
| `FilterSettingsFragment` | adapter bind 改用 Rule 結構顯示 |
| `SettingsFragment` | 按鈕/summary 維持不變，內部查詢方式改用 RuleEngine |
| `TimelineAdapter` / `AppSourceAdapter` / `ChannelAdapter` | 長按回調參數不變（仍傳 packageName/channelId），DialogHelper 內部處理 |

### 資料層

| 檔案 | 影響 |
|------|------|
| `NotificationFilter.kt` | 重寫 — Matcher / RuleAction / Rule / RuleEngine |
| `AppPreferences` | key 遷移或新增 v2 key |
| `FilterRuleStoreMatchTest` | 重寫測試覆蓋 |

---

## 實作階段

### Phase 1：核心引擎（不影響 UI） ✅ 完成

1. ✅ 定義 `Matcher` sealed interface + 5 種子類（`core/filter/Rule.kt`）
2. ✅ 定義 `RuleAction` sealed interface
3. ✅ 定義 `Rule` data class + `MatchContext` + `ActionType` + `KeywordField`
4. ✅ 實作 `RuleEngine`（取代 `FilterRuleStore`）：load / save / CRUD / match（`core/filter/RuleEngine.kt`）
5. ✅ JSON 序列化 v2 + v1 遷移邏輯 + 匯出匯入 + 自動備份
6. ✅ 單元測試（`RuleTest.kt` 30 case + `RuleEngineMatchTest.kt` 15 case）

### Phase 2：Service 層切換 ✅ 完成

1. ✅ `processNotification()` / `processRemoval()` / `processRankingUpdate()` 切換到 `RuleEngine` + `MatchContext`
2. ✅ `checkAutoDismiss()` 切換到 `RuleEngine`
3. ✅ `onCreate()` 單一 `RuleEngine.load(this)` 取代多次 `FilterRuleStore.load()`

### Phase 3：UI 層切換 ✅ 完成

1. ✅ `FilterRuleDialogHelper` 切換到 `ActionType` / `Rule` / `RuleEngine`，內部建構 Matcher 列表 + RuleAction
2. ✅ `FilterSettingsFragment` 切換到 `ActionType` / `Rule` / `RuleEngine`，nav arg `"actionType"`
3. ✅ `SettingsFragment` 切換到 `ActionType` / `RuleEngine`
4. ✅ `TimelineFragment` / `ArchiveFragment` 長按入口參數更新
5. ✅ `nav_graph.xml` argument 名稱和預設值更新

### Phase 4：Keyword / Channel UI 擴充 ✅ 完成

1. ✅ `KeywordMatcher` UI：pattern + field checkbox (TITLE/TEXT/BIG_TEXT/SUB_TEXT) + regex switch
2. ✅ `ChannelPropertyMatcher` UI：importance dropdown + groupId autocomplete
3. ✅ `MatchContext` 完整填充 content 欄位（title/text/bigText/subText）和 channel 屬性（importance/groupId）
4. ✅ FilterSettingsFragment adapter 顯示 keyword/channelProperty 資訊
5. ✅ 整合測試覆蓋 keyword/channelProperty 匹配

### Phase 5：清理 ✅ 完成

1. ✅ 移除舊 `FilterCategory` / `FilterRule` / `FilterRuleStore`（`NotificationFilter.kt` 刪除）
2. ✅ 移除 `AppPreferences` 中的 v1 key 方法和常數
3. ✅ 移除 `Rule.fromV1()` 遷移方法
4. ✅ 移除 `RuleEngine` 中的 `importV1()` / `migrateFromV1()` 邏輯
5. ✅ 移除舊測試（`FilterRuleTest.kt` / `FilterRuleStoreMatchTest.kt`）

---

## 向後相容

- 匯入 v1 JSON 自動轉換為 v2 Rule 結構
- SharedPreferences 遷移：首次 load v2 時自動讀取 v1 keys 並轉換
- 匯出 v2 JSON 包含 `"version": 2`；匯入時以 version 欄位判斷格式

## 風險評估

| 風險 | 影響 | 緩解 |
|------|------|------|
| Dialog UI 複雜度增加 | 使用者操作步驟變多 | 常用場景保留快捷路徑（如長按 prefill 自動建立 PackageMatcher + ChannelMatcher） |
| KeywordMatcher 效能 | 每條通知逐一檢查 keyword | regex 預編譯；keyword 規則數通常 < 5 |
| Channel 屬性查詢延遲 | API 26-27 需 DB 查詢 | 首次通知無 channel 記錄時 skip ChannelPropertyMatcher |
| v1→v2 遷移失敗 | 規則遺失 | 遷移前保留 v1 key 不刪除，僅在確認 v2 寫入成功後才清除 |
