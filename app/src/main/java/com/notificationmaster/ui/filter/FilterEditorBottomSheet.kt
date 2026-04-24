package com.notificationmaster.ui.filter

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notificationmaster.NotificationMasterApp
import com.notificationmaster.R
import com.notificationmaster.data.db.dao.count
import com.notificationmaster.data.filter.EventFilterSpec
import com.notificationmaster.data.filter.FieldPredicate
import com.notificationmaster.data.filter.FilterFieldWhitelist
import com.notificationmaster.data.filter.FilterPresetRepository
import com.notificationmaster.data.filter.Op
import com.notificationmaster.data.filter.OrderBy
import com.notificationmaster.databinding.SheetFilterEditorBinding
import com.notificationmaster.databinding.ItemFilterPredicateBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * 自訂篩選 BottomSheet：表單式多條件組合 → [EventFilterSpec]，
 * 套用時透過 callback 回傳；可選擇儲存為 preset。
 */
class FilterEditorBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetFilterEditorBinding? = null
    private val binding get() = _binding!!

    private lateinit var initialSpec: EventFilterSpec
    private var editingPresetName: String? = null

    private val predicateRows = mutableListOf<PredicateRow>()
    private val specFlow = MutableStateFlow(EventFilterSpec.All)
    private var previewJob: Job? = null

    private var onApply: ((EventFilterSpec) -> Unit)? = null
    private var onSaveAsPreset: ((String, EventFilterSpec) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetFilterEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        populateFromSpec(initialSpec)

        // 欄位變動 → 重算 spec → 更新 preview
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshSpec() }
        }
        binding.editKeyword.addTextChangedListener(textWatcher)
        binding.editPackage.addTextChangedListener(textWatcher)
        binding.editChannel.addTextChangedListener(textWatcher)
        binding.editLimit.addTextChangedListener(textWatcher)
        binding.chipGroupBehavior.setOnCheckedStateChangeListener { _, _ -> refreshSpec() }

        setupOrderDropdown()

        binding.btnAddCondition.setOnClickListener { addPredicateRow(FieldPredicate(
            field = FilterFieldWhitelist.fields.keys.first(),
            op = Op.EQ,
            value = 0
        )) }

        binding.btnApply.setOnClickListener {
            onApply?.invoke(specFlow.value)
            dismiss()
        }
        binding.btnSavePreset.setOnClickListener {
            showSavePresetDialog()
        }

        observePreview()
        refreshSpec()
    }

    private fun setupOrderDropdown() {
        val labels = listOf(
            getString(R.string.filter_editor_order_post_desc),
            getString(R.string.filter_editor_order_post_asc),
            getString(R.string.filter_editor_order_capture_desc)
        )
        val values = listOf(OrderBy.PostTimeDesc, OrderBy.PostTimeAsc, OrderBy.CaptureTimeDesc)
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
        binding.dropdownOrder.setAdapter(adapter)
        val idx = values.indexOf(initialSpec.orderBy).coerceAtLeast(0)
        binding.dropdownOrder.setText(labels[idx], false)
        binding.dropdownOrder.setOnItemClickListener { _, _, _, _ -> refreshSpec() }
    }

    private fun populateFromSpec(spec: EventFilterSpec) {
        binding.editKeyword.setText(spec.keyword.orEmpty())
        binding.editPackage.setText(spec.packageName.orEmpty())
        binding.editChannel.setText(spec.channelId.orEmpty())
        binding.editLimit.setText(spec.limit?.toString().orEmpty())
        binding.chipBDedup.isChecked = spec.deduplicate
        binding.chipBAudible.isChecked = (spec.isAudible == true)
        binding.chipBHeadsup.isChecked = (spec.likelyHeadsup == true)
        binding.chipBDismissed.isChecked = (spec.isRemoved == true)

        // 既有 predicates
        predicateRows.clear()
        binding.containerPredicates.removeAllViews()
        spec.extraPredicates.forEach { addPredicateRow(it) }
    }

    private fun addPredicateRow(initial: FieldPredicate) {
        val rowBinding = ItemFilterPredicateBinding.inflate(layoutInflater, binding.containerPredicates, false)
        val row = PredicateRow(rowBinding, initial, onChanged = { refreshSpec() }, onRemove = {
            // capture 變數於 lambda 建構後才對應到實際 row
        })
        // 完成 row 建立後再補 onRemove callback
        rowBinding.btnRemove.setOnClickListener {
            binding.containerPredicates.removeView(rowBinding.root)
            predicateRows.remove(row)
            refreshSpec()
        }
        row.setup(requireContext())
        predicateRows.add(row)
        binding.containerPredicates.addView(rowBinding.root)
        refreshSpec()
    }

    private fun refreshSpec() {
        val spec = buildSpecFromUi()
        specFlow.value = spec
    }

    private fun buildSpecFromUi(): EventFilterSpec {
        val keyword = binding.editKeyword.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val pkg = binding.editPackage.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val channel = binding.editChannel.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val limit = binding.editLimit.text?.toString()?.trim()?.toIntOrNull()

        val orderLabels = listOf(
            getString(R.string.filter_editor_order_post_desc),
            getString(R.string.filter_editor_order_post_asc),
            getString(R.string.filter_editor_order_capture_desc)
        )
        val orderValues = listOf(OrderBy.PostTimeDesc, OrderBy.PostTimeAsc, OrderBy.CaptureTimeDesc)
        val selectedOrder = orderLabels.indexOf(binding.dropdownOrder.text?.toString())
            .takeIf { it >= 0 }?.let { orderValues[it] } ?: OrderBy.PostTimeDesc

        val preds = predicateRows.mapNotNull { it.toPredicate() }

        return EventFilterSpec(
            packageName = pkg,
            channelId = channel,
            isAudible = if (binding.chipBAudible.isChecked) true else null,
            likelyHeadsup = if (binding.chipBHeadsup.isChecked) true else null,
            isRemoved = if (binding.chipBDismissed.isChecked) true else null,
            keyword = keyword,
            deduplicate = binding.chipBDedup.isChecked,
            orderBy = selectedOrder,
            limit = limit,
            extraPredicates = preds
        )
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observePreview() {
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            specFlow
                .debounce(250)
                .distinctUntilChanged()
                .flatMapLatest { spec ->
                    runCatching {
                        val dao = NotificationMasterApp.getInstance().database.notificationDao()
                        dao.count(spec)
                    }.getOrElse { flowOf(-1) }
                }
                .collectLatest { count ->
                    if (_binding == null) return@collectLatest
                    binding.textPreview.text = when {
                        count < 0 -> getString(R.string.filter_editor_preview_loading)
                        else -> getString(R.string.filter_editor_preview, count)
                    }
                }
        }
    }

    private fun showSavePresetDialog() {
        val editText = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            setText(editingPresetName.orEmpty())
        }
        val inputLayout = com.google.android.material.textfield.TextInputLayout(
            requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(R.string.preset_save_name_hint)
            setPadding(48, 16, 48, 0)
            addView(editText)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.preset_save_title)
            .setView(inputLayout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = editText.text?.toString()?.trim().orEmpty()
                val err = validatePresetName(name)
                if (err != null) {
                    inputLayout.error = err
                    return@setOnClickListener
                }
                onSaveAsPreset?.invoke(name, specFlow.value)
                dialog.dismiss()
                this@FilterEditorBottomSheet.dismiss()
            }
        }
        dialog.show()
    }

    private fun validatePresetName(name: String): String? {
        if (name.isBlank()) return getString(R.string.preset_save_name_empty)
        if (name in FilterPresetRepository.SYSTEM_NAMES) return getString(R.string.preset_save_name_reserved)
        val repo = FilterPresetRepository.getInstance(requireContext())
        val existing = repo.getPreset(name)
        // 允許覆寫既有：editing 狀態下 name 相同 or name 不存在；提示由呼叫端決定
        return if (existing != null && existing.name != editingPresetName) {
            // 不阻擋，僅提示
            null
        } else null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewJob?.cancel()
        _binding = null
    }

    companion object {
        fun show(
            fm: FragmentManager,
            initial: EventFilterSpec,
            editingPresetName: String?,
            onApply: (EventFilterSpec) -> Unit,
            onSaveAsPreset: (name: String, spec: EventFilterSpec) -> Unit
        ) {
            val sheet = FilterEditorBottomSheet().apply {
                this.initialSpec = initial
                this.editingPresetName = editingPresetName
                this.onApply = onApply
                this.onSaveAsPreset = onSaveAsPreset
            }
            sheet.show(fm, "FilterEditor")
        }
    }
}

/** 單一 extraPredicate row 的 UI 綁定與狀態轉換 */
private class PredicateRow(
    val binding: ItemFilterPredicateBinding,
    var current: FieldPredicate,
    val onChanged: () -> Unit,
    @Suppress("UNUSED_PARAMETER") val onRemove: () -> Unit
) {
    private val fieldKeys: List<String> = FilterFieldWhitelist.fields.keys.toList()

    fun setup(ctx: android.content.Context) {
        val fieldLabels = fieldKeys.map { fieldLabel(ctx, it) }
        val opLabels = Op.values().map { it.name }

        val fieldAdapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, fieldLabels)
        binding.dropdownField.setAdapter(fieldAdapter)
        val initFieldIdx = fieldKeys.indexOf(current.field).coerceAtLeast(0)
        binding.dropdownField.setText(fieldLabels[initFieldIdx], false)

        val opAdapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, opLabels)
        binding.dropdownOp.setAdapter(opAdapter)
        binding.dropdownOp.setText(current.op.name, false)

        binding.editValue.setText(current.value?.toString().orEmpty())

        binding.dropdownField.setOnItemClickListener { _, _, _, _ -> emit() }
        binding.dropdownOp.setOnItemClickListener { _, _, _, _ -> emit() }
        binding.editValue.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { emit() }
        })
    }

    private fun emit() {
        onChanged()
    }

    fun toPredicate(): FieldPredicate? {
        val fieldLabel = binding.dropdownField.text?.toString() ?: return null
        val field = fieldKeys.firstOrNull { fieldLabel(binding.root.context, it) == fieldLabel } ?: return null
        val opName = binding.dropdownOp.text?.toString() ?: return null
        val op = runCatching { Op.fromName(opName) }.getOrNull() ?: return null
        val rawValue = binding.editValue.text?.toString()?.trim().orEmpty()

        val def = FilterFieldWhitelist.require(field)
        return try {
            when (op) {
                Op.IS_NULL, Op.IS_NOT_NULL -> FieldPredicate(field, op)
                Op.IN -> {
                    val list = rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (list.isEmpty()) return null
                    FieldPredicate(field, op, values = list.map { parseValue(def.type, it) })
                }
                else -> {
                    if (rawValue.isEmpty()) return null
                    FieldPredicate(field, op, value = parseValue(def.type, rawValue))
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseValue(type: FilterFieldWhitelist.Type, raw: String): Any = when (type) {
        FilterFieldWhitelist.Type.INT -> raw.toInt()
        FilterFieldWhitelist.Type.LONG -> raw.toLong()
        FilterFieldWhitelist.Type.BOOL -> raw.equals("true", ignoreCase = true) || raw == "1"
        FilterFieldWhitelist.Type.TEXT -> raw
    }
}

private fun fieldLabel(ctx: android.content.Context, key: String): String {
    val resName = "field_$key"
    val resId = ctx.resources.getIdentifier(resName, "string", ctx.packageName)
    return if (resId != 0) ctx.getString(resId) else key
}

