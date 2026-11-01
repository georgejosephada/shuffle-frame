package com.gjnet.shuffleframe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

class AlbumAdapter(
    private val onToggle: (Album, Boolean) -> Unit,
    private val onClick: (AlbumSummary) -> Unit,
) : ListAdapter<AlbumSummary, AlbumAdapter.Holder>(Diff) {

    private val covers = LruCache<String, Bitmap>(64)

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val title: TextView = v.findViewById(R.id.title)
        val counts: TextView = v.findViewById(R.id.counts)
        val status: TextView = v.findViewById(R.id.status)
        val toggle: MaterialSwitch = v.findViewById(R.id.enabled)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val s = getItem(position)
        val a = s.album
        val ctx = h.itemView.context
        h.title.text = a.title
        h.counts.text = buildString {
            append(ctx.resources.getQuantityString(R.plurals.photos, s.photos, s.photos))
            append(" · ")
            append(ctx.resources.getQuantityString(R.plurals.videos, s.videos, s.videos))
            if (s.downloaded < s.total) append(" · ").append(ctx.getString(R.string.downloaded_of, s.downloaded, s.total))
            if (s.hidden > 0) append(" · ").append(ctx.getString(R.string.hidden_count, s.hidden))
        }
        val (text, color) = when {
            a.lastError != null -> a.lastError to R.color.error
            a.lastSyncAt == 0L -> ctx.getString(R.string.not_synced_yet) to R.color.muted
            else -> ctx.getString(
                R.string.synced_ago,
                DateUtils.getRelativeTimeSpanString(a.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            ) to R.color.muted
        }
        h.status.text = text
        h.status.setTextColor(ContextCompat.getColor(ctx, color))
        h.itemView.alpha = if (a.enabled) 1f else 0.55f

        val file = Store.coverFile(a.key)
        val cacheKey = "${a.key}:${file.lastModified()}"
        val bmp = covers.get(cacheKey) ?: if (file.exists()) BitmapFactory.decodeFile(file.path)?.also { covers.put(cacheKey, it) } else null
        if (bmp != null) h.cover.setImageBitmap(bmp) else h.cover.setImageResource(R.drawable.ic_photo_placeholder)

        h.toggle.setOnCheckedChangeListener(null)
        h.toggle.isChecked = a.enabled
        h.toggle.setOnCheckedChangeListener { _, checked -> onToggle(a, checked) }
        h.itemView.setOnClickListener { onClick(s) }
    }

    private object Diff : DiffUtil.ItemCallback<AlbumSummary>() {
        override fun areItemsTheSame(a: AlbumSummary, b: AlbumSummary) = a.album.key == b.album.key
        override fun areContentsTheSame(a: AlbumSummary, b: AlbumSummary) = a == b
    }
}
