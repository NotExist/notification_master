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
        holder.bind(entityIds[position], position + 1, entityIds.size)
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

        fun bind(entityId: Long, groupIndex: Int, groupTotal: Int) {
            lifecycleScope.launch {
                val events = withContext(Dispatchers.IO) {
                    eventDao.getEventsByNotificationIdSync(entityId)
                }

                // 建立 GroupHeader + EventItem 列表
                val items: List<EventListItem> = buildList {
                    if (events.isNotEmpty()) {
                        add(EventListItem.GroupHeader(
                            notificationId = entityId,
                            groupIndex = groupIndex,
                            groupTotal = groupTotal,
                            firstEventTime = events.first().eventTime,
                            lastEventTime = events.last().eventTime,
                            isCurrent = true // 在 pager 中始終標記為目前頁
                        ))
                    }
                    addAll(events.map { EventListItem.EventItem(it) })
                }
                eventAdapter.submitList(items)
            }
        }
    }
}
