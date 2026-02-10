Notification Master 是一個原生的 Android Notification 管理程式

需要盡可能向前支援，對不同版本 Android 使用最適合的 API 版本以達成相同效果



# 功能
收取並保留所有 Notification
去重
保留更新歷程
保留媒體
保留點擊動作(重新觸發)
按照來源 (App/Channel) 記錄歸檔
按時間排序(回顧)
搜尋
記錄到 calendar (ical)


---

# 標籤與分類定義

## 使用者介面標籤（UI Tags）

時間軸、搜尋結果和通知詳情頁會顯示以下標籤，點擊可查看說明。

### 時間軸 / 搜尋頁（4 種）

| 標籤 | 出現條件 | 說明 |
|------|---------|------|
| **Ongoing** | `FLAG_ONGOING_EVENT` | 持續通知，不可被滑動移除。固定在通知欄上方的「進行中」區塊。常見於音樂播放、導航、通話計時 |
| **FG Service** | `FLAG_FOREGROUND_SERVICE` | 前景服務通知，App 正在執行前景服務時系統強制顯示。服務結束前不會消失。常見於音樂播放中、VPN、即時定位 |
| **Heads-up** | 推斷值 | 推斷此通知可能以螢幕頂部浮動方式顯示。API 26+：Channel importance ≥ HIGH；API 21–25：priority ≥ HIGH 且設有音效或震動。此為推斷，實際受勿擾模式等因素影響 |
| **Summary** | `FLAG_GROUP_SUMMARY` | 群組摘要通知，代表一組通知的折疊總覽。例如「你有 5 則訊息」 |

### 通知詳情頁（額外 3 種）

| 標籤 | 出現條件 | 說明 |
|------|---------|------|
| **AutoCancel** | `FLAG_AUTO_CANCEL` | 使用者點擊後自動從通知欄移除。大多數一般通知都有此標記 |
| **Bubble** | `hasBubbleMetadata` (API 29+) | 氣泡通知，可浮動在其他 App 之上。由來源 App 設定，使用者可在系統設定中停用 |
| **Custom View** | 任一 `hasCustom*View` | 通知使用自訂 RemoteViews。部分視覺資訊可能無法完整擷取至文字欄位，原始資料保留在 extras 中 |

---

## 生命週期事件類型 (EventType)

每筆通知記錄都會伴隨一或多筆生命週期事件，追蹤通知從出現到消失的完整歷程。

| 事件 | 意義 | 觸發時機 |
|------|------|---------|
| `INITIAL` | 既有通知 | `onListenerConnected()` 時呼叫 `getActiveNotifications()` 取得的通知。代表服務啟動前已存在於通知欄的通知，無法得知其原始 POSTED 時間點 |
| `POSTED` | 新通知發布 | `onNotificationPosted()` 且該 `notification_key` 不存在於資料庫時 |
| `UPDATED` | 通知內容更新 | `onNotificationPosted()` 且該 `notification_key` 已存在於資料庫時。App 對同一通知呼叫 `notify()` 更新內容（如進度條、新訊息）會觸發此事件 |
| `REMOVED` | 通知移除 | `onNotificationRemoved()` 觸發。伴隨 `removal_reason` 記錄移除原因 |
| `RANKING` | 排序/重要性變更 | `onNotificationRankingUpdate()` 觸發。系統重新排序通知時發生，記錄 rank、importance 等 Ranking 快照 |


## 移除原因分類 (RemovalReasonCategory)

通知被移除時，系統回傳一個 `reason` 整數值。本 App 將其分類為以下類別：

| 分類 | 對應 SDK 常數 | 意義 | 是否使用者操作 |
|------|-------------|------|--------------|
| `USER_CLICK` | `REASON_CLICK` | 使用者點擊了通知或其 Action 按鈕 | 是 |
| `USER_SNOOZE` | `REASON_SNOOZED` | 使用者將通知暫停（snooze） | 是 |
| `APP_CANCEL` | `REASON_APP_CANCEL` 或 `REASON_CANCEL` | 來源 App 主動呼叫 `cancel()` 取消單筆通知 | 否 |
| `APP_CANCEL_ALL` | `REASON_APP_CANCEL_ALL` 或 `REASON_CANCEL_ALL` | 來源 App 呼叫 `cancelAll()` 取消所有通知 | 否 |
| `LISTENER_OR_SWIPE` | `REASON_LISTENER_CANCEL` | Listener 取消或使用者滑動移除。**系統無法區分這兩種操作**，因此合併為一個分類 | 可能 |
| `TIMEOUT` | `REASON_TIMEOUT` | 通知超時自動消失。Heads-up 浮動通知約 4-5 秒後下降回通知欄時**不會**觸發此原因；此處指的是 `setTimeoutAfter()` (API 26+) 設定的超時 | 否 |
| `CHANNEL_BANNED` | `REASON_CHANNEL_BANNED` | 使用者停用了該通知所屬的 Channel (API 26+) | 是 |
| `UNINSTALLED` | reason 值 `15` | 來源 App 被解除安裝。**此常數未包含在公開 SDK 中**，為 AOSP 內部定義值 | 是 |
| `OTHER` | 其餘值 | 未知或未來新增的移除原因 | 不明 |


## 通知 Flags（布林標記）

Android `Notification.flags` 是一個位元遮罩（bitmask），本 App 將各位元拆解為獨立布林欄位儲存。

### 持久性相關 Flags

| 欄位 | 對應 Flag | 意義 | 常見場景 |
|------|----------|------|---------|
| `isOngoing` | `FLAG_ONGOING_EVENT` | **持續通知**，不可被使用者滑動移除。會固定在通知欄上方的「進行中」區塊 | 音樂播放、導航、通話計時 |
| `isForegroundService` | `FLAG_FOREGROUND_SERVICE` | **前景服務通知**，Android 要求前景服務必須顯示一則 ongoing 通知。隱含 `isOngoing = true` | 音樂 App 播放中、VPN、即時位置追蹤 |
| `isNoClear` | `FLAG_NO_CLEAR` | **不可清除通知**，使用者按「清除全部」時不會被移除，但仍可單獨滑動移除（除非同時為 ongoing） | 系統提醒、重要的持久性通知 |
| `isAutoCancel` | `FLAG_AUTO_CANCEL` | **點擊後自動取消**，使用者點擊通知後自動從通知欄移除 | 絕大多數一般通知 |

### 顯示/行為相關 Flags

| 欄位 | 對應 Flag | 意義 | 常見場景 |
|------|----------|------|---------|
| `isHighPriority` | `FLAG_HIGH_PRIORITY` | 高優先級標記（**已棄用**，API 26+ 應使用 Channel importance） | 舊版 App 的重要通知 |
| `isLocalOnly` | `FLAG_LOCAL_ONLY` | **僅本機通知**，不會同步到穿戴裝置或其他連接設備 | 本機 debug 通知、WiFi 狀態 |
| `isGroupSummary` | `FLAG_GROUP_SUMMARY` | **群組摘要通知**，代表一組通知的摘要/總覽。系統顯示通知群組折疊狀態時使用此通知 | 多則訊息折疊為「你有 5 則訊息」、多封郵件折疊為「3 封新郵件」 |


## 持久性類型 (PersistenceType)

由本 App 根據 Flags 組合推斷的分類，**非 Android 原生概念**。優先級由高到低判斷：

| 類型 | 判斷條件 | 意義 |
|------|---------|------|
| `FOREGROUND_SERVICE` | `FLAG_FOREGROUND_SERVICE` 為 true | 前景服務綁定的通知。只要服務在執行，通知就不會消失 |
| `ONGOING` | `FLAG_ONGOING_EVENT` 為 true（且非前景服務） | 持續性通知，通常表示某項操作正在進行 |
| `PINNED` | `FLAG_NO_CLEAR` 為 true（且非前兩者） | 固定通知，不被「清除全部」移除，但非持續性操作 |
| `TRANSIENT` | 不符合以上任一條件 | 一般短暫通知，使用者可滑動移除或按清除全部移除 |


## Heads-up 浮動通知 (likelyHeadsup)

Heads-up 是 API 21 引入的浮動通知，在螢幕頂部以小視窗彈出約 4-5 秒。**系統不會透過 API 告知通知是否以 Heads-up 顯示**，因此本 App 使用以下規則推斷：

| API 範圍 | 判斷條件 |
|---------|---------|
| API 26+ | Channel `importance >= IMPORTANCE_HIGH`（即 HIGH 或 MAX） |
| API 21-25 | `priority >= PRIORITY_HIGH` **且** 設有 `sound` 或 `vibrate` |

此欄位為**推斷值 (likely)**，實際顯示行為還受勿擾模式（DND）、App 前景狀態等因素影響。

### Heads-up 生命週期注意事項
- Heads-up 消失（下降回通知欄）時**不會**觸發 `onNotificationRemoved()`
- 通知仍留在通知欄中，直到被使用者、App 或系統真正移除


## 優先級與重要性

Android 使用兩套機制控制通知的顯示層級，分別適用於不同 API 版本：

### Priority（API 21-25，API 26+ 已棄用）

| 值 | 常數 | 意義 |
|----|------|------|
| -2 | `PRIORITY_MIN` | 最低，可能不顯示在狀態列 |
| -1 | `PRIORITY_LOW` | 低，排序靠下 |
| 0 | `PRIORITY_DEFAULT` | 預設 |
| 1 | `PRIORITY_HIGH` | 高，可能觸發 Heads-up |
| 2 | `PRIORITY_MAX` | 最高，通常觸發 Heads-up |

### Importance（API 26+，由 NotificationChannel 決定）

| 值 | 常數 | 意義 | 顯示行為 |
|----|------|------|---------|
| 0 | `IMPORTANCE_NONE` | 無 | 不顯示，但可在設定中看到 |
| 1 | `IMPORTANCE_MIN` | 最低 | 不發聲、不震動、不出現在狀態列 |
| 2 | `IMPORTANCE_LOW` | 低 | 不發聲、不震動 |
| 3 | `IMPORTANCE_DEFAULT` | 預設 | 發聲 |
| 4 | `IMPORTANCE_HIGH` | 高 | 發聲 + **Heads-up 浮動顯示** |

本 App 資料庫中 `importance` 欄位在 API < 26 時儲存為 `-1`，表示不適用。


## 可見性 (Visibility)

控制通知在鎖定螢幕上的顯示方式（API 21+）：

| 值 | 常數 | 意義 |
|----|------|------|
| 1 | `VISIBILITY_PUBLIC` | 完整顯示在鎖屏上 |
| 0 | `VISIBILITY_PRIVATE` | 鎖屏上只顯示基本資訊（預設） |
| -1 | `VISIBILITY_SECRET` | 鎖屏上完全不顯示 |


## 通知分類 (Category)

由來源 App 自行設定的語意分類，本 App 僅記錄不做判斷。常見值：

| 常數 | 意義 |
|------|------|
| `call` | 來電 |
| `msg` | 即時訊息 |
| `email` | 電子郵件 |
| `event` | 日曆事件 |
| `alarm` | 鬧鐘 |
| `progress` | 進行中操作的進度 |
| `social` | 社群網路通知 |
| `transport` | 媒體播放控制 |
| `sys` | 系統通知 |
| `service` | 背景服務狀態 |
| `err` | 錯誤 |
| `recommendation` | 推薦 |
| `status` | 裝置/帳號狀態 |
| `reminder` | 提醒 |
| `navigation` | 導航 |

此欄位為**選填**，許多 App 不設定 category，此時值為 `null`。


## 語意動作 (SemanticAction)

API 28+ 引入，標示 Action 按鈕的語意類型。讓系統可以推測按鈕功能而不只依賴按鈕文字。

| 值 | 常數 | 意義 |
|----|------|------|
| 0 | `NONE` | 未指定 |
| 1 | `REPLY` | 回覆 |
| 2 | `MARK_AS_READ` | 標為已讀 |
| 3 | `MARK_AS_UNREAD` | 標為未讀 |
| 4 | `DELETE` | 刪除 |
| 5 | `ARCHIVE` | 封存 |
| 6 | `MUTE` | 靜音 |
| 7 | `UNMUTE` | 取消靜音 |
| 8 | `THUMBS_UP` | 讚 |
| 9 | `THUMBS_DOWN` | 倒讚 |
| 10 | `CALL` | 撥打電話 |

API < 28 時一律記錄為 `0` (NONE)。


## 群組通知 (Notification Group)

API 21+ 支援將多則通知歸為同一群組。

| 欄位 | 意義 |
|------|------|
| `groupKey` | 群組識別碼，由來源 App 設定。同一 `groupKey` 的通知會被系統群組顯示 |
| `sortKey` | 群組內排序鍵，決定通知在群組內的順序 |
| `isGroupSummary` | 是否為群組摘要通知（見上方 Flags 說明） |

### 自動群組
API 24+ 起，系統會在同一 App 發出 4 則以上未指定 group 的通知時**自動群組**，並產生系統生成的 `groupKey`。


## Ranking 資訊

由系統維護的通知排序資訊，透過 `onNotificationRankingUpdate()` 更新。**需 API 24+**。

| 欄位 | 意義 |
|------|------|
| `rankingRank` | 通知在全域排序中的位置（0 起算，越小越重要） |
| `isAmbient` | 是否為低優先級（ambient）通知，不發聲、不震動 |
| `isSuspended` | 是否因 App 被暫停而隱藏（API 28+） |
| `suppressedVisualEffects` | 被抑制的視覺效果位元遮罩（如勿擾模式下隱藏通知彈出） |


## Bubble 通知

API 29+ 引入的氣泡通知，可浮動在其他 App 之上。本 App 記錄 `hasBubbleMetadata` 標示通知是否附帶 BubbleMetadata。

觸發條件：來源 App 使用 `setBubbleMetadata()` 設定氣泡資料，且使用者未停用該 App 的 Bubble 功能。


## 裝置狀態快照 (DeviceState)

**非 Notification API 資料**，獨立於通知資訊儲存。記錄通知到達瞬間的裝置執行環境。

| 欄位 | 值域 | 意義 |
|------|------|------|
| `ringerMode` | 0=靜音 / 1=震動 / 2=正常 | `AudioManager.RINGER_MODE_*` |
| `isScreenOn` | true/false | 螢幕是否亮起 (`PowerManager.isInteractive()`) |
| `batteryLevel` | 0-100 / -1=無法取得 | 電池百分比 |
| `batteryStatus` | 1=UNKNOWN / 2=CHARGING / 3=DISCHARGING / 4=NOT_CHARGING / 5=FULL / -1=無法取得 | `BatteryManager.BATTERY_STATUS_*` |
| `isConnected` | true/false/null | 是否有網路連線。null 表示無 `ACCESS_NETWORK_STATE` 權限 |
| `connectionType` | `ConnectivityManager.TYPE_*` / -1=無連線 | 網路類型（WiFi、行動數據等） |


## 去重用 Hash (contentHash)

組合 `packageName + title + text + bigText` 計算 SHA-256。
- **儲存層不去重**：每次 `onNotificationPosted()` 都完整儲存
- **檢視層可選去重**：使用者可切換「顯示全部」或「去重顯示」，後者以 `contentHash` 分群只顯示最新一筆


---

# 待辦事項

- [ ] **設定 CI 共用 debug keystore** — 確保所有 GitHub Actions debug build 共用相同簽名，避免安裝時因簽名不一致而需先解除安裝
  ```bash
  # 1. 產生 debug keystore（密碼/alias 需符合 AGP 預設值）
  keytool -genkeypair \
    -alias androiddebugkey \
    -keypass android \
    -keystore debug.keystore \
    -storepass android \
    -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 \
    -validity 10950

  # 2. Base64 編碼
  base64 -w 0 debug.keystore
  # 將輸出複製

  # 3. 到 GitHub → Repository Settings → Secrets and variables → Actions
  #    新增 Repository Secret：
  #      Name:  DEBUG_KEYSTORE_BASE64
  #      Value: （貼上步驟 2 的輸出）
  ```
  CI workflow 已在 `.github/workflows/android.yml` 中加入還原步驟，設定 Secret 後即生效。
  未設定時會自動跳過，fallback 至 AGP 每次自動產生的 keystore。

- [ ] **Toast 訊息擷取（AccessibilityService）** — 擷取不經 NotificationManager 的 Toast 短暫訊息
  - **背景**：Toast 走 `INotificationManager.enqueueToast()` 路徑，NotificationListenerService 無法攔截
  - **方案**：實作 AccessibilityService，監聽 `AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED`
  - **權限**：需要 `android.permission.BIND_ACCESSIBILITY_SERVICE`（系統權限，使用者需在設定中手動開啟）
  - **限制**：
    - 僅能取得 Toast 文字內容（`event.text`），無法取得來源 package（`event.packageName` 為顯示 Toast 的 window 所屬 app，不一定是觸發者）
    - AccessibilityService 審核政策嚴格，Google Play 上架可能需要額外說明用途
    - 需要在 `res/xml/` 下新增 accessibility service config XML
  - **需要的檔案變更**：
    - `AndroidManifest.xml`：宣告 AccessibilityService + meta-data
    - `res/xml/accessibility_service_config.xml`：新增配置（`typeNotificationStateChanged`）
    - 新增 Service 類別：監聽事件 → 寫入 DB（需設計新 Entity 或擴充現有結構）
    - `PermissionDescriptions.kt`：已預先加入權限說明（目前標記為「尚未實作」）
