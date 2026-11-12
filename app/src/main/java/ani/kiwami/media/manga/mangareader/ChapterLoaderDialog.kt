package ani.kiwami.media.manga.mangareader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ani.kiwami.BottomSheetDialogFragment
import ani.kiwami.R
import ani.kiwami.currActivity
import ani.kiwami.databinding.BottomSheetSelectorBinding
import ani.kiwami.getThemeColor
import ani.kiwami.media.MediaDetailsViewModel
import ani.kiwami.media.MediaSingleton
import ani.kiwami.media.manga.MangaChapter
import ani.kiwami.others.getSerialized
import ani.kiwami.tryWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Serializable

class ChapterLoaderDialog : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSelectorBinding? = null
    private val binding get() = _binding!!

    val model: MediaDetailsViewModel by activityViewModels()

    private val launch: Boolean by lazy { arguments?.getBoolean("launch", false) ?: false }
    private val chp: MangaChapter by lazy { arguments?.getSerialized("next")!! }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        var loaded = false
        binding.selectorAutoListContainer.visibility = View.VISIBLE
        binding.selectorListContainer.visibility = View.GONE

        binding.selectorTitle.text = getString(R.string.loading_chap_number, chp.number)
        binding.selectorCancel.setOnClickListener {
            dismiss()
        }

        model.getMedia().observe(viewLifecycleOwner) { m ->
            if (m != null && !loaded) {
                val selected = m.selected ?: model.loadSelected(m).also { m.selected = it }
                loaded = true
                binding.selectorAutoText.text = chp.title
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = model.loadMangaChapterImages(
                        chp,
                        selected
                    )
                    val activity = currActivity()
                    activity?.runOnUiThread {
                        if (!isAdded || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        tryWith { dismiss() }
                        if (success) {
                            if (launch) {
                                MediaSingleton.media = m
                                val intent = Intent(
                                    activity,
                                    MangaReaderActivity::class.java
                                )
                                activity.startActivity(intent)
                            }
                        } else {
                            ani.kiwami.snackString(activity.getString(R.string.error_loading_data, chp.title ?: chp.number))
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSelectorBinding.inflate(inflater, container, false)
        val window = dialog?.window
        window?.statusBarColor = Color.TRANSPARENT
        window?.navigationBarColor =
            requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(next: MangaChapter, launch: Boolean = false) = ChapterLoaderDialog().apply {
            arguments = bundleOf("next" to next as Serializable, "launch" to launch)
        }
    }
}
