package com.example.screenshotviewer

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.chrisbanes.photoview.PhotoView

class ImagePagerAdapter(
    private val images: List<FileItem>,
    private val baseUrl: String,
    private val onImageClick: (() -> Unit)? = null
) : RecyclerView.Adapter<ImagePagerAdapter.ImageViewHolder>() {

    // 存储合并后的 bitmap，key 为 position
    private val bitmapOverrides = mutableMapOf<Int, Bitmap>()

    fun overrideBitmap(position: Int, bitmap: Bitmap) {
        bitmapOverrides[position] = bitmap
        notifyItemChanged(position)
    }

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val photoView: PhotoView = view.findViewById(R.id.photoView)
        var baseScale: Float = 1f  // 保存基础缩放比例（适应宽度）
        var isZoomedByDoubleTap: Boolean = false  // 跟踪是否通过双击放大
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_pager, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val item = images[position]
        val imageUrl = "$baseUrl/stream/${item.path}"

        // 重置双击状态
        holder.isZoomedByDoubleTap = false

        // 设置自定义双击监听器
        holder.photoView.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onImageClick?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val photoView = holder.photoView
                val baseScale = holder.baseScale

                if (holder.isZoomedByDoubleTap) {
                    // 已经放大，返回原始大小（以点击点为中心）
                    photoView.setScale(baseScale, e.x, e.y, true)
                    holder.isZoomedByDoubleTap = false
                } else {
                    // 放大一倍（以点击点为中心）
                    val targetScale = baseScale * 2.0f
                    photoView.setScale(targetScale, e.x, e.y, true)
                    holder.isZoomedByDoubleTap = true
                }
                return true
            }

            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                return false
            }
        })

        // 如果有合并后的 bitmap，直接使用，跳过网络加载
        val overrideBitmap = bitmapOverrides[position]
        if (overrideBitmap != null) {
            holder.photoView.setImageBitmap(overrideBitmap)
            return
        }

        // 显示 placeholder
        holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)

        // 使用 Glide 加载图片，自动处理大图片和内存管理
        Glide.with(holder.photoView.context)
            .asBitmap()
            .load(imageUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(bitmap: Bitmap, transition: Transition<in Bitmap>?) {
                    // 设置图片
                    holder.photoView.setImageBitmap(bitmap)

                    // 使用ViewTreeObserver等待PhotoView完成布局
                    holder.photoView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            holder.photoView.viewTreeObserver.removeOnGlobalLayoutListener(this)

                            // 等待PhotoView完成初始化后再调整缩放
                            holder.photoView.post {
                                try {
                                    // 获取PhotoView当前的显示宽度（PhotoView自己计算的）
                                    val currentDisplayRect = holder.photoView.displayRect
                                    val currentDisplayWidth = currentDisplayRect?.width() ?: 0f

                                    // 获取PhotoView容器的实际宽度
                                    val viewWidth = holder.photoView.width.toFloat()

                                    android.util.Log.d("ImagePagerAdapter", "ViewWidth: $viewWidth, Current DisplayRect width: $currentDisplayWidth")

                                    if (currentDisplayWidth > 0 && viewWidth > 0) {
                                        // 计算需要的缩放倍数（相对于当前显示）
                                        val scaleFactor = viewWidth / currentDisplayWidth
                                        val currentScale = holder.photoView.scale
                                        val targetScale = currentScale * scaleFactor

                                        android.util.Log.d("ImagePagerAdapter", "Scale factor: $scaleFactor")
                                        android.util.Log.d("ImagePagerAdapter", "Current scale: $currentScale, Target scale: $targetScale")

                                        // 限制targetScale在合理范围内 (0.1 到 50.0) - 放宽上限以支持大屏
                                        val safeTargetScale = targetScale.coerceIn(0.1f, 50.0f)

                                        if (safeTargetScale != targetScale) {
                                            android.util.Log.w("ImagePagerAdapter", "Target scale $targetScale out of range, clamped to $safeTargetScale")
                                        }

                                        // 设置缩放范围 - 确保min < target < max
                                        val minScale = (safeTargetScale * 0.5f).coerceAtLeast(0.1f)
                                        val maxScale = (safeTargetScale * 5f).coerceAtMost(50.0f)
                                        val medScale = (safeTargetScale * 2f).coerceIn(minScale, maxScale)

                                        android.util.Log.d("ImagePagerAdapter", "Scale range: min=$minScale, target=$safeTargetScale, med=$medScale, max=$maxScale")

                                        // 按正确的顺序设置scale: 先max, 再medium, 最后min
                                        holder.photoView.maximumScale = maxScale
                                        holder.photoView.mediumScale = medScale
                                        holder.photoView.minimumScale = minScale

                                        // 保存基础缩放比例
                                        holder.baseScale = safeTargetScale
                                        holder.isZoomedByDoubleTap = false

                                        // 应用缩放,并设置焦点为顶部中心,使图片顶部对齐屏幕顶部
                                        // setScale(scale, focalX, focalY, animate)
                                        // focalX = viewWidth/2 (水平居中), focalY = 0 (顶部对齐)
                                        holder.photoView.setScale(safeTargetScale, viewWidth / 2f, 0f, false)

                                        // 验证结果
                                        holder.photoView.postDelayed({
                                            val finalDisplayRect = holder.photoView.displayRect
                                            android.util.Log.d("ImagePagerAdapter", "Final - scale: ${holder.photoView.scale}")
                                            android.util.Log.d("ImagePagerAdapter", "Final - DisplayRect width: ${finalDisplayRect?.width()}")
                                        }, 100)
                                    } else {
                                        android.util.Log.w("ImagePagerAdapter", "Invalid dimensions: viewWidth=$viewWidth, currentDisplayWidth=$currentDisplayWidth")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ImagePagerAdapter", "Error setting scale", e)
                                    e.printStackTrace()
                                }
                            }
                        }
                    })
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    holder.photoView.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                }
            })
    }

    override fun getItemCount() = images.size
}
