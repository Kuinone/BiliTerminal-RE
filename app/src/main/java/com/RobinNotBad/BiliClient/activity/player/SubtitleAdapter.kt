package com.RobinNotBad.BiliClient.activity.player

import com.RobinNotBad.BiliClient.ui.appearance.ColorScheme
import com.RobinNotBad.BiliClient.ui.appearance.GlassStyle
import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.model.SubtitleLink

class SubtitleAdapter : RecyclerView.Adapter<SubtitleAdapter.Holder>() {
    private var list: Array<SubtitleLink>? = null
    private var context: Context? = null

    var listener: OnItemClickListener? = null
    var selectedItemIndex = 0
        set(value) {
            val previous = field
            field = value
            notifyItemChanged(previous)
            notifyItemChanged(value)
        }

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setData(episodeList: Array<SubtitleLink>) {
        this.list = episodeList
        selectedItemIndex = 0
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        this.context = parent.context
        val view = LayoutInflater.from(this.context).inflate(R.layout.cell_subtitle, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (position < 0 || list == null || position >= list!!.size)
            return
        holder.listener = listener
        holder.bind(position, selectedItemIndex == position)
    }

    override fun getItemCount(): Int {
        return list?.size ?: 0
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

        var listener: OnItemClickListener? = null
        private val button: Button = itemView.findViewById(R.id.btn)

        fun bind(currentIndex: Int, isSelected: Boolean) {
            if (currentIndex < 0 || list == null || currentIndex >= list!!.size)
                return
            button.text = list!![currentIndex].lang
            // 播放器永远暗色：取暗色表，亮色模式下全局 getter 会给出深色文字 / 浅色卡片
            val dark = ColorScheme.darkColors()
            if (GlassStyle.isEnabled()) {
                button.setTextColor(if (isSelected) dark.SECONDARY else dark.TEXT_PRIMARY)
                button.background = GlassStyle.choiceBackground(button.context, isSelected)
            } else if (isSelected) {
                button.setTextColor(dark.ON_PRIMARY)
                button.setBackgroundColor(dark.PRIMARY)
            } else {
                button.setTextColor(dark.TEXT_PRIMARY)
                button.setBackgroundColor(dark.CARD)
            }
            button.setOnClickListener {
                selectedItemIndex = currentIndex
                listener?.onItemClick(currentIndex)
            }
        }
    }
}