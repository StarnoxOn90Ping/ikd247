package ani.dantotsu.media.manga

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.NumberPicker
import androidx.lifecycle.coroutineScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.currContext
import ani.dantotsu.databinding.ItemChapterListBinding
import ani.dantotsu.databinding.ItemEpisodeCompactBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.anime.applySelectionStyle
import ani.dantotsu.setAnimation
import ani.dantotsu.util.SizeFormatter
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MangaChapterAdapter(
    private var type: Int,
    private val media: Media,
    private val fragment: MangaReadFragment,
    var arr: ArrayList<MangaChapter> = arrayListOf(),
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        /** Partial bind: only refresh the selected/unselected look of a card. */
        private const val PAYLOAD_SELECTION = "selection"
    }

    /** Chapters picked in multi-select mode, keyed by uniqueNumber() (number + scanlator). */
    val selectedChapters = LinkedHashSet<String>()
    var onSelectionChanged: ((Int) -> Unit)? = null

    fun isSelecting(): Boolean = selectedChapters.isNotEmpty()

    fun toggleSelection(chapter: MangaChapter) {
        val key = chapter.uniqueNumber()
        if (!selectedChapters.remove(key)) selectedChapters.add(key)
        val index = arr.indexOfFirst { it.uniqueNumber() == key }
        if (index >= 0) notifyItemChanged(index, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke(selectedChapters.size)
    }

    fun selectAll() {
        arr.forEach { selectedChapters.add(it.uniqueNumber()) }
        notifyItemRangeChanged(0, arr.size, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke(selectedChapters.size)
    }

    fun clearSelection() {
        if (selectedChapters.isEmpty()) return
        selectedChapters.clear()
        notifyItemRangeChanged(0, arr.size, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke(0)
    }

    private fun applySelection(holder: RecyclerView.ViewHolder, chapter: MangaChapter, animate: Boolean) {
        val card: android.widget.FrameLayout = when (holder) {
            is ChapterCompactViewHolder -> holder.binding.itemEpisodeCont
            is ChapterListViewHolder -> holder.binding.root
            else -> return
        }
        applySelectionStyle(card, selectedChapters.contains(chapter.uniqueNumber()), animate)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            if (position in arr.indices) applySelection(holder, arr[position], true)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            1 -> ChapterCompactViewHolder(
                ItemEpisodeCompactBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            0 -> ChapterListViewHolder(
                ItemChapterListBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return type
    }

    override fun getItemCount(): Int = arr.size

    inner class ChapterCompactViewHolder(val binding: ItemEpisodeCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) {
                    if (isSelecting()) toggleSelection(arr[pos])
                    else fragment.onMangaChapterClick(arr[pos])
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) toggleSelection(arr[pos])
                true
            }
        }
    }

    private val activeDownloads = mutableSetOf<String>()
    private val downloadedChapters = mutableSetOf<String>()

    fun isDownloading(chapterNumber: String): Boolean = activeDownloads.contains(chapterNumber)
    fun isDownloaded(chapterNumber: String): Boolean = downloadedChapters.contains(chapterNumber)

    fun isDownloaded(chapter: MangaChapter): Boolean {
        val unique = chapter.uniqueNumber()
        val num = chapter.number
        val title = chapter.title
        val titleUnique = title?.let { "$it-${chapter.scanlator ?: "Unknown"}" }
        return downloadedChapters.contains(unique) ||
                downloadedChapters.contains(num) ||
                (title != null && downloadedChapters.contains(title)) ||
                (titleUnique != null && downloadedChapters.contains(titleUnique))
    }

    fun startDownload(chapterNumber: String) {
        activeDownloads.add(chapterNumber)
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber || it.number == chapterNumber }
        if (position != -1) {
            val chapter = arr[position]
            activeDownloads.add(chapter.uniqueNumber())
            activeDownloads.add(chapter.number)
            notifyItemChanged(position)
        }
    }

    fun stopDownload(chapterNumber: String) {
        activeDownloads.remove(chapterNumber)
        downloadedChapters.add(chapterNumber)
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber || it.number == chapterNumber }
        if (position != -1) {
            val chapter = arr[position]
            activeDownloads.remove(chapter.uniqueNumber())
            activeDownloads.remove(chapter.number)
            downloadedChapters.add(chapter.uniqueNumber())
            downloadedChapters.add(chapter.number)
            chapter.progress = "Downloaded"
            notifyItemChanged(position)
        }
    }

    fun deleteDownload(chapterNumber: MangaChapter) {
        downloadedChapters.remove(chapterNumber.uniqueNumber())
        downloadedChapters.remove(chapterNumber.number)
        activeDownloads.remove(chapterNumber.uniqueNumber())
        activeDownloads.remove(chapterNumber.number)
        chapterNumber.title?.let { downloadedChapters.remove(it) }
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber.uniqueNumber() }
        if (position != -1) {
            arr[position].progress = ""
            notifyItemChanged(position)
        }
    }

    fun purgeDownload(chapterNumber: String) {
        activeDownloads.remove(chapterNumber)
        downloadedChapters.remove(chapterNumber)
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber || it.number == chapterNumber }
        if (position != -1) {
            val chapter = arr[position]
            activeDownloads.remove(chapter.uniqueNumber())
            activeDownloads.remove(chapter.number)
            downloadedChapters.remove(chapter.uniqueNumber())
            downloadedChapters.remove(chapter.number)
            chapter.progress = ""
            notifyItemChanged(position)
        }
    }

    fun updateDownloadProgress(chapterNumber: String, progress: Int) {
        updateDownloadProgress(chapterNumber, progress, -1L, -1L)
    }

    fun updateDownloadProgress(
        chapterNumber: String,
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ) {
        // Find the position of the chapter and notify only that item
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber }
        if (position != -1) {
            arr[position].progress = buildDownloadProgressText(
                progress,
                downloadedBytes,
                estimatedTotalBytes
            )

            notifyItemChanged(position)
        }
    }

    private fun buildDownloadProgressText(
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ): String {
        val hasDownloaded = downloadedBytes > 0L
        val hasEstimatedTotal = estimatedTotalBytes > 0L
        return if (hasDownloaded && hasEstimatedTotal) {
            "Downloading: $progress% (${SizeFormatter.formatBytes(downloadedBytes)} / ${SizeFormatter.formatBytes(estimatedTotalBytes)} est.)"
        } else if (hasEstimatedTotal) {
            "Downloading: $progress% (~${SizeFormatter.formatBytes(estimatedTotalBytes)} est.)"
        } else {
            "Downloading: $progress%"
        }
    }

    fun downloadNChaptersFrom(position: Int, n: Int) {
        //download next n chapters
        if (position < 0 || position >= arr.size) return
        for (i in 0..<n) {
            if (position + i < arr.size) {
                val chapter = arr[position + i]
                val chapterNumber = chapter.uniqueNumber()
                if (activeDownloads.contains(chapterNumber)) {
                    //do nothing
                    continue
                } else if (isDownloaded(chapter)) {
                    //do nothing
                    continue
                } else {
                    fragment.onMangaChapterDownloadClick(chapter)
                }
            }
        }
    }

    fun deleteNChaptersFrom(position: Int, n: Int){
        //delete next n chapters
        if (position < 0 || position >= arr.size) return
        for (i in 0..<n) {
            if (position + i < arr.size) {
                val chapter = arr[position + i]
                val chapterNumber = chapter.uniqueNumber()
                if(activeDownloads.contains(chapterNumber) || activeDownloads.contains(chapter.number)){
                    fragment.onMangaChapterStopDownloadClick(chapter)
                }
                else if (isDownloaded(chapter)) {
                    fragment.onMangaChapterRemoveDownloadClick(chapter)
                }
            }
        }
    }

    inner class ChapterListViewHolder(val binding: ItemChapterListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val activeCoroutines = mutableSetOf<String>()
        fun bind(chapter: MangaChapter, progress: String?) {
            val chapterNumber = chapter.uniqueNumber()
            if (progress != null) {
                binding.itemChapterTitle.visibility = View.VISIBLE
                binding.itemChapterTitle.text = "$progress"
            } else {
                binding.itemChapterTitle.visibility = View.GONE
                binding.itemChapterTitle.text = ""
            }

            if (media.format == "LOCAL") {
                binding.itemDownload.visibility = View.GONE
                return
            }
            binding.itemDownload.visibility = View.VISIBLE

            if (activeDownloads.contains(chapterNumber) || activeDownloads.contains(chapter.number)) {
                // Show spinner
                binding.itemDownload.setImageResource(R.drawable.ic_sync)
                startOrContinueRotation(chapter) {
                    binding.itemDownload.rotation = 0f
                }
            } else if (isDownloaded(chapter)) {
                // Show delete icon
                binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                binding.itemDownload.rotation = 0f
            } else {
                // Show download icon
                binding.itemDownload.setImageResource(R.drawable.ic_download_24)
                binding.itemDownload.rotation = 0f
            }

        }

        private fun startOrContinueRotation(chapter: MangaChapter, resetRotation: () -> Unit) {
            val key = chapter.uniqueNumber()
            if (!isRotationCoroutineRunningFor(key)) {
                val scope = fragment.lifecycle.coroutineScope
                scope.launch {
                    activeCoroutines.add(key)
                    while (activeDownloads.contains(chapter.uniqueNumber()) || activeDownloads.contains(chapter.number)) {
                        binding.itemDownload.animate().rotationBy(360f).setDuration(1000)
                            .setInterpolator(
                                LinearInterpolator()
                            ).start()
                        delay(1000)
                    }
                    activeCoroutines.remove(key)
                    resetRotation()
                }
            }
        }

        private fun isRotationCoroutineRunningFor(chapterNumber: String): Boolean {
            return chapterNumber in activeCoroutines
        }

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) {
                    if (isSelecting()) toggleSelection(arr[pos])
                    else fragment.onMangaChapterClick(arr[pos])
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) toggleSelection(arr[pos])
                true
            }
            binding.itemDownload.setOnClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val chapter = arr[bindingAdapterPosition]
                    val chapterNumber = chapter.uniqueNumber()
                    if (activeDownloads.contains(chapterNumber) || activeDownloads.contains(chapter.number)) {
                        fragment.onMangaChapterStopDownloadClick(chapter)
                        return@setOnClickListener
                    } else if (isDownloaded(chapter)) {
                        it.context.customAlertDialog().apply {
                            setTitle("Delete Chapter")
                            setMessage("Are you sure you want to delete ${chapter.number}?")
                            setPosButton(R.string.delete) {
                                fragment.onMangaChapterRemoveDownloadClick(chapter)
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                        return@setOnClickListener
                    } else {
                        fragment.onMangaChapterDownloadClick(chapter)
                    }
                }
            }
            binding.itemDownload.setOnLongClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size){
                    val chapter = arr[bindingAdapterPosition]
                    val chapterNumber = chapter.uniqueNumber()
                    if(activeDownloads.contains(chapterNumber) || isDownloaded(chapter)){
                        fragment.requireContext().customAlertDialog().apply {
                            setTitle("Multi Chapter Deleter")
                            setMessage("Enter the number of chapters to delete")
                            val input = NumberPicker(currContext())
                            input.minValue = 1
                            input.maxValue = itemCount - bindingAdapterPosition
                            input.value = 1
                            setCustomView(input)
                            setPosButton(R.string.ok) {
                                binding.root.context.customAlertDialog().apply {
                                    setTitle("Delete Chapters")
                                    setMessage("Are you sure you want to delete the next ${input.value} chapters?")
                                    setPosButton(R.string.yes) {
                                        deleteNChaptersFrom(bindingAdapterPosition, input.value)
                                    }
                                    setNegButton(R.string.no)
                                }.show()
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                    }
                    else{
                        //Alert dialog asking for the number of chapters to download
                        it.context.customAlertDialog().apply {
                            setTitle("Multi Chapter Downloader")
                            setMessage("Enter the number of chapters to download")
                            val input = NumberPicker(currContext())
                            input.minValue = 1
                            input.maxValue = itemCount - bindingAdapterPosition
                            input.value = 1
                            setCustomView(input)
                            setPosButton("OK") {
                                downloadNChaptersFrom(bindingAdapterPosition, input.value)
                            }
                            setNegButton("Cancel")
                            show()
                        }
                    }
                }
                true
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ChapterCompactViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)
                val ep = arr[position]
                val parsedNumber = MediaNameAdapter.findChapterNumber(ep.number)?.toInt()
                binding.itemEpisodeNumber.text = parsedNumber?.toString() ?: ep.number
                if (media.userProgress != null) {
                    if ((MediaNameAdapter.findChapterNumber(ep.number)
                            ?: 9999f) <= media.userProgress!!.toFloat()
                    )
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                    else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        // long-press now selects the chapter (see the view holders)
                    }
                }
            }

            is ChapterListViewHolder -> {
                val binding = holder.binding
                val ep = arr[position]
                holder.bind(ep, ep.progress)
                setAnimation(fragment.requireContext(), holder.binding.root)
                binding.itemChapterNumber.text = ep.number

                if (ep.date != null) {
                    binding.itemChapterDateLayout.visibility = View.VISIBLE
                    binding.itemChapterDate.text = formatDate(ep.date)
                }
                if (ep.scanlator != null) {
                    binding.itemChapterDateLayout.visibility = View.VISIBLE
                    binding.itemChapterScan.text = ep.scanlator.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(
                            Locale.ROOT
                        ) else it.toString()
                    }
                }
                if (formatDate(ep.date) == "" || ep.scanlator == null) {
                    binding.itemChapterDateDivider.visibility = View.GONE
                } else binding.itemChapterDateDivider.visibility = View.VISIBLE

                if (ep.progress.isNullOrEmpty()) {
                    binding.itemChapterTitle.visibility = View.GONE
                } else binding.itemChapterTitle.visibility = View.VISIBLE

                if (media.userProgress != null) {
                    if ((MediaNameAdapter.findChapterNumber(ep.number)
                            ?: 9999f) <= media.userProgress!!.toFloat()
                    ) {
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                        binding.itemEpisodeViewed.visibility = View.VISIBLE
                    } else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeViewed.visibility = View.GONE
                        // long-press now selects the chapter (see the view holders)
                    }
                } else {
                    binding.itemEpisodeViewedCover.visibility = View.GONE
                    binding.itemEpisodeViewed.visibility = View.GONE
                }
            }
        }
        if (position in arr.indices) applySelection(holder, arr[position], false)
    }

    fun updateType(t: Int) {
        type = t
    }

    fun formatDate(timestamp: Long?): String {
        timestamp ?: return "" // Return empty string if timestamp is null

        val targetDate = Date(timestamp)

        if (targetDate < Date(946684800000L)) { // January 1, 2000 (who want dates before that?)
            return ""
        }

        val currentDate = Date()
        val difference = currentDate.time - targetDate.time

        return when (val daysDifference = difference / (1000 * 60 * 60 * 24)) {
            0L -> {
                val hoursDifference = difference / (1000 * 60 * 60)
                val minutesDifference = (difference / (1000 * 60)) % 60

                when {
                    hoursDifference > 0 -> "$hoursDifference hour${if (hoursDifference > 1) "s" else ""} ago"
                    minutesDifference > 0 -> "$minutesDifference minute${if (minutesDifference > 1) "s" else ""} ago"
                    else -> "Just now"
                }
            }

            1L -> "1 day ago"
            in 2..6 -> "$daysDifference days ago"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(targetDate)
        }
    }

}
