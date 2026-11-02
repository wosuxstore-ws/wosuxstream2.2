package com.freetv.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.freetv.app.databinding.FragmentBrowseBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The old grid screen ("all channels"), moved out of MainActivity in Phase 17 with identical behavior:
 * grid, Source / Search / Favorites / Refresh buttons, dialogs, focus restore after the player.
 *
 * Phase 24: with the optional argument `group` (see newInstance) the same grid shows ONE category only ("See all" on a Home row):
 * a back button and the category name on top, no Source / Search / Favorites / Refresh buttons, and the shared search text and
 * "favorites only" switch of the ViewModel are ignored, so the whole category is always shown. Without the argument nothing changed.
 */
class BrowseFragment : Fragment() {

    private var _b: FragmentBrowseBinding? = null
    private val b get() = _b!!

    private val vm: MainViewModel by activityViewModels<MainViewModel>()
    private lateinit var adapter: ChannelAdapter

    private var visible: List<Channel> = emptyList()
    private var cameFromPlayer = false

    // Phase 24: the category this screen is limited to (null = the normal grid of all channels). Read from the arguments in onCreate.
    private var group: String? = null
    private var groupJob: Job? = null
    // true once the category list was built at least once (keeps the "no channels" text from flashing while it is being built)
    private var groupReady = false

    // "Favorites only" of the ViewModel counts only in the normal grid, never in a category grid
    private val favOnly: Boolean get() = group == null && vm.favoritesOnly

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        group = arguments?.getString(ARG_GROUP)?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentBrowseBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // As many columns as fit: ~2 on a phone, ~6-8 on a TV
        val dm = resources.displayMetrics
        val span = ((dm.widthPixels / dm.density) / 150f).toInt().coerceIn(2, 10)

        adapter = ChannelAdapter(
            isFavorite = { Prefs.isFavorite(it.url) },
            onClick = { _, pos -> play(pos) },
            onLongClick = { ch, pos -> toggleFavorite(ch, pos) }
        )
        b.list.layoutManager = GridLayoutManager(requireContext(), span)
        b.list.adapter = adapter
        (b.list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        // Category grid: back button + category name instead of the app name and the button row
        val g = group
        if (g != null) {
            b.groupBar.isVisible = true
            b.groupTitle.text = g
            b.appTitle.isVisible = false
            b.toolbar.isVisible = false
            b.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
            // TV: put the focus on the back button (a view that was focused on the screen below is gone now).
            // Posted, because the view is not attached to the window yet. No effect in touch mode.
            view.post { _b?.btnBack?.requestFocus() }
        }

        b.btnSource.setOnClickListener { showSourceMenu() }
        b.btnSearch.setOnClickListener { showSearch() }
        b.btnFav.setOnClickListener {
            vm.favoritesOnly = !vm.favoritesOnly
            applyFilter()
        }
        b.btnRefresh.setOnClickListener { vm.load(force = true) }

        val owner = viewLifecycleOwner
        vm.channels.observe(owner) { applyFilter() }
        vm.loading.observe(owner) { isLoading ->
            b.progress.isVisible = isLoading
            b.empty.isVisible = visible.isEmpty() && !isLoading && (group == null || groupReady)
        }
        vm.source.observe(owner) { s -> b.btnSource.text = getString(R.string.source_btn, s.name) }
        vm.message.observe(owner) { msg ->
            if (msg != null) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                vm.message.value = null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (cameFromPlayer) {
            cameFromPlayer = false
            // Favorites may have changed inside the player
            if (favOnly) applyFilter() else adapter.notifyItemRangeChanged(0, adapter.itemCount)

            // Put the cursor back on the channel that was playing last
            val idx = ChannelStore.index
            if (idx in visible.indices) {
                val list = b.list // captured, so the delayed lambda never touches the binding after the view is gone
                list.scrollToPosition(idx)
                list.postDelayed({
                    list.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
                }, 150)
            }
        }
    }

    override fun onDestroyView() {
        groupJob?.cancel()
        groupJob = null
        groupReady = false
        _b = null
        super.onDestroyView()
    }

    private fun applyFilter() {
        val g = group
        if (g != null) {
            applyGroup(g)
            return
        }

        val base = if (vm.favoritesOnly) Prefs.favorites() else vm.channels.value.orEmpty()
        val q = vm.query.trim()
        visible = if (q.isEmpty()) base else base.filter {
            it.name.contains(q, ignoreCase = true) || (it.group?.contains(q, ignoreCase = true) == true)
        }
        adapter.submitList(visible)

        b.count.text = getString(R.string.channels_count, visible.size)
        b.btnFav.setText(if (vm.favoritesOnly) R.string.favorites_on else R.string.favorites_off)
        b.btnSearch.text = if (q.isEmpty()) getString(R.string.search) else getString(R.string.search_active, q)
        b.empty.setText(if (vm.favoritesOnly) R.string.empty_favorites else R.string.empty_list)
        b.empty.isVisible = visible.isEmpty() && vm.loading.value != true
    }

    // ---------- category grid (Phase 24) ----------

    /** Builds the list of the category off the main thread, then shows it. A newer call replaces an older one. */
    private fun applyGroup(name: String) {
        val all = vm.channels.value.orEmpty()
        groupJob?.cancel()
        groupJob = viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.Default) { filterGroup(all, name) }
            visible = list
            groupReady = true
            adapter.submitList(list)
            b.count.text = getString(R.string.channels_count, list.size)
            b.empty.setText(R.string.empty_group)
            b.empty.isVisible = list.isEmpty() && vm.loading.value != true
        }
    }

    // Playlist order is kept. Pure function, safe on Dispatchers.Default.
    private fun filterGroup(all: List<Channel>, name: String): List<Channel> {
        val out = ArrayList<Channel>()
        for (c in all) {
            if (inGroup(c, name)) out.add(c)
        }
        return out
    }

    /** True when one of the ';'-separated categories of the channel's group equals `name` (trimmed, ignoring case). `name` must not be empty. */
    private fun inGroup(c: Channel, name: String): Boolean {
        val g = c.group ?: return false
        val n = g.length
        var start = 0
        while (start <= n) {
            var end = g.indexOf(';', start)
            if (end < 0) end = n
            var s = start
            var e = end
            while (s < e && g[s].isWhitespace()) s++
            while (e > s && g[e - 1].isWhitespace()) e--
            if (e - s == name.length && g.regionMatches(s, name, 0, name.length, ignoreCase = true)) return true
            start = end + 1
        }
        return false
    }

    private fun play(position: Int) {
        if (position !in visible.indices) return
        ChannelStore.playlist = visible
        ChannelStore.index = position
        cameFromPlayer = true
        startActivity(Intent(requireContext(), PlayerActivity::class.java))
    }

    private fun toggleFavorite(ch: Channel, pos: Int) {
        val added = Prefs.toggleFavorite(ch)
        Toast.makeText(requireContext(), if (added) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
        if (favOnly && !added) applyFilter() else adapter.notifyItemChanged(pos)
    }

    // ---------- dialogs (all work with a TV remote) ----------

    private fun showSourceMenu() {
        val items = arrayOf(
            getString(R.string.src_all),
            getString(R.string.src_country),
            getString(R.string.src_category),
            getString(R.string.src_language),
            getString(R.string.src_custom)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.choose_source)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> vm.setSource(Sources.ALL)
                    1 -> pickSource(R.string.src_country, Sources.countries)
                    2 -> pickSource(R.string.src_category, Sources.categories)
                    3 -> pickSource(R.string.src_language, Sources.languages)
                    4 -> promptCustomUrl()
                }
            }
            .show()
    }

    private fun pickSource(titleRes: Int, list: List<Source>) {
        val names = list.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setItems(names) { _, i -> vm.setSource(list[i]) }
            .show()
    }

    private fun showSearch() {
        promptText(
            title = getString(R.string.search_title),
            hint = getString(R.string.search_hint),
            initial = vm.query,
            okLabel = getString(R.string.search),
            onOk = {
                vm.query = it
                applyFilter()
            },
            onClear = {
                vm.query = ""
                applyFilter()
            }
        )
    }

    private fun promptCustomUrl() {
        promptText(
            title = getString(R.string.src_custom),
            hint = getString(R.string.custom_hint),
            initial = Prefs.customUrl,
            okLabel = getString(R.string.load),
            onOk = { text ->
                val url = text.trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    Prefs.customUrl = url
                    vm.setSource(Source(getString(R.string.custom_name), url))
                } else {
                    Toast.makeText(requireContext(), R.string.custom_invalid, Toast.LENGTH_LONG).show()
                }
            },
            onClear = null
        )
    }

    private fun promptText(
        title: String,
        hint: String,
        initial: String,
        okLabel: String,
        onOk: (String) -> Unit,
        onClear: (() -> Unit)?
    ) {
        val ctx = requireContext()
        val input = EditText(ctx)
        input.setText(initial)
        input.setHint(hint)
        input.setSingleLine(true)
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setSelection(input.text.length)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(ctx)
        box.setPadding(pad, pad / 3, pad, 0)
        box.addView(
            input,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(okLabel) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
        if (onClear != null) {
            builder.setNeutralButton(R.string.clear) { _, _ -> onClear() }
        }
        builder.show()
    }

    companion object {
        private const val ARG_GROUP = "group"

        /**
         * group = name of the category to limit the grid to (matched against the ';'-separated parts of each channel's group, ignoring case),
         * or null for the normal grid of all channels. The name travels in the fragment arguments, so it survives rotation.
         */
        fun newInstance(group: String?): BrowseFragment {
            val f = BrowseFragment()
            if (group != null) {
                val args = Bundle()
                args.putString(ARG_GROUP, group)
                f.arguments = args
            }
            return f
        }
    }
}
