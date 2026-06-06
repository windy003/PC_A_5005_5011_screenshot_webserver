package com.example.screenshotviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class ImageAdapter(
    private var images: List<FileItem>,
    private val baseUrl: String,
    private val onImageClick: (FileItem) -> Unit,
    private val onDownloadClick: (FileItem) -> Unit,
    private val onDeleteClick: (FileItem) -> Unit
) : RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.imageView)
        val imageName: TextView = view.findViewById(R.id.imageName)
        val downloadButton: Button = view.findViewById(R.id.downloadButton)
        val deleteButton: Button = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val item = images[position]
        holder.imageName.text = item.name

        // 使用 Glide 加载图片，自动处理大图片和内存管理
        val imageUrl = "$baseUrl/stream/${item.path}"
        Glide.with(holder.imageView.context)
            .load(imageUrl)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .error(android.R.drawable.ic_menu_close_clear_cancel)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .override(400, 400)  // 缩略图只需要小尺寸
            .centerCrop()
            .into(holder.imageView)

        holder.imageView.setOnClickListener { onImageClick(item) }
        holder.downloadButton.setOnClickListener { onDownloadClick(item) }
        holder.deleteButton.setOnClickListener { onDeleteClick(item) }
    }

    override fun getItemCount() = images.size

    fun updateImages(newImages: List<FileItem>) {
        images = newImages
        notifyDataSetChanged()
    }
}
