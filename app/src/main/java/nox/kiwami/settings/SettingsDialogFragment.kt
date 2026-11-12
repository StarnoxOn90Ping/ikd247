package nox.kiwami.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import nox.kiwami.BottomSheetDialogFragment
import nox.kiwami.R
import nox.kiwami.Refresh
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.databinding.BottomSheetSettingsBinding
import nox.kiwami.getThemeColor
import nox.kiwami.home.AnimeFragment
import nox.kiwami.home.HomeFragment
import nox.kiwami.home.LoginFragment
import nox.kiwami.home.MangaFragment
import nox.kiwami.incognitoNotification
import nox.kiwami.loadImage
import nox.kiwami.profile.ProfileActivity
import nox.kiwami.profile.activity.FeedActivity
import nox.kiwami.profile.notification.NotificationActivity
import nox.kiwami.setSafeOnClickListener
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.startMainActivity
import nox.kiwami.util.customAlertDialog
import eu.kanade.tachiyomi.util.system.getSerializableCompat

class SettingsDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var pageType: PageType
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageType = arguments?.getSerializableCompat("pageType") as? PageType ?: PageType.HOME
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val window = dialog?.window
        window?.statusBarColor = Color.CYAN
        window?.navigationBarColor =
            requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
        val notificationIcon = if (Anilist.unreadNotificationCount > 0) {
            R.drawable.ic_round_notifications_active_24
        } else {
            R.drawable.ic_round_notifications_none_24
        }
        binding.settingsNotification.setImageResource(notificationIcon)

        if (Anilist.token != null) {
            binding.settingsLogin.setText(R.string.logout)
            binding.settingsLogin.setOnClickListener {
                requireContext().customAlertDialog().apply {
                    setTitle(R.string.logout)
                    setMessage(R.string.logout_confirm)
                    setPosButton(R.string.yes) {
                        Anilist.removeSavedToken()
                        startMainActivity(requireActivity())
                    }
                    setNegButton(R.string.no)
                    show()
                }
            }
            binding.settingsUsername.text = Anilist.username
            binding.settingsUserAvatar.loadImage(Anilist.avatar)
        } else {
            binding.settingsUsername.visibility = View.GONE
            binding.settingsLogin.setText(R.string.login)
            binding.settingsLogin.setOnClickListener {
                dismiss()
                Anilist.loginIntent(requireActivity())
            }
        }
        binding.settingsNotificationCount.isVisible = Anilist.unreadNotificationCount > 0
        binding.settingsNotificationCount.text = Anilist.unreadNotificationCount.toString()
        binding.settingsUserAvatar.setOnClickListener {
            ContextCompat.startActivity(
                requireContext(), Intent(requireContext(), ProfileActivity::class.java)
                    .putExtra("userId", Anilist.userid), null
            )
        }

        binding.settingsIncognito.isChecked = PrefManager.getVal(PrefName.Incognito)
        binding.settingsIncognito.setOnCheckedChangeListener { _, isChecked ->
            // Added check to ensure fragment is still active before updating
            if (isAdded) {
                PrefManager.setVal(PrefName.Incognito, isChecked)
                incognitoNotification(requireContext())
            }
        }

        binding.settingsExtensionSettings.setSafeOnClickListener {
            startActivity(Intent(activity, ExtensionsActivity::class.java))
            dismiss()
        }

        binding.settingsDownloadQueue.setSafeOnClickListener {
            startActivity(Intent(activity, DownloadQueueActivity::class.java))
            dismiss()
        }

        binding.settingsSettings.setSafeOnClickListener {
            startActivity(Intent(activity, SettingsActivity::class.java))
            dismiss()
        }

        binding.settingsActivity.setSafeOnClickListener {
            startActivity(Intent(activity, FeedActivity::class.java))
            dismiss()
        }

        binding.settingsNotification.setOnClickListener {
            startActivity(Intent(activity, NotificationActivity::class.java))
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        enum class PageType {
            MANGA, ANIME, HOME
        }

        fun newInstance(pageType: PageType): SettingsDialogFragment {
            val fragment = SettingsDialogFragment()
            val args = Bundle()
            args.putSerializable("pageType", pageType)
            fragment.arguments = args
            return fragment
        }
    }
}
