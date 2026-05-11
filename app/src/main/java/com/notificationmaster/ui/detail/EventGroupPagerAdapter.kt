package com.notificationmaster.ui.detail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notificationmaster.data.db.dao.NotificationEventDao
import com.notificationmaster.data.db.entity.NotificationEventEntity
import com.notificationmaster.databinding.ItemEventPageBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewPager2 Adapter：每頁對應一個 NotificationEntity 的生命週期事件
 */
class EventGroupPagerAdapter(
    private val eventDao: NotificationEventDao,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onEventClick: (NotificationEventEntity) -> Unit
) : RecyclerView.Adapter<EventGroupPagerAdapter.PageViewHolder>() {

    private var entityIds: List<Long> = emptyList()

    fun submitEntityIds(ids: List<Long>) {
        entityIds = ids
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = entityIds.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemEventPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(entityIds[position])
    }

    inner class PageViewHolder(
        private val binding: ItemEventPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val eventAdapter = NotificationEventAdapter(
            onItemClick = onEventClick
        )

        init {
            binding.recyclerPageEvents.apply {
                adapter = eventAdapter
                layoutManager = LinearLayoutManager(context)
            }
        }

        fun bind(entityId: Long) {
            lifecycleScope.launch {
                // Plan 2 過渡：events 已改以 notification_key 關聯，不再有 notification_id Long。
                //              此處 entityId 對應的 NotificationEntity.id 仍可用於從 NotificationDao
                //              取得 notification_key，再用 key 查 events。Phase 7 移除 ViewPager 時整段刪。
                val events = withContext(Dispatchers.IO) {
                    val db = com.notificationmaster.NotificationMasterApp.getInstance().database
                    val key = db.notificationDao().getById(entityId)?.notificationKey
                        ?: return@withContext emptyList()
                    eventDao.getEventsByKeySync(key)
                }

                // 建立 GroupHeader + EventItem 列表
                val items: List<EventListItem> = buildList {
                    if (events.isNotEmpty()) {
                        add(EventListItem.GroupHeader(
                            notificationId = entityId,
                            firstEventTime = events.first().eventTime,
                            lastEventTime = events.last().eventTime
                        ))
                    }
                    addAll(events.map { EventListItem.EventItem(it) })
                }
                eventAdapter.submitList(items)
            }
        }
    }
}
