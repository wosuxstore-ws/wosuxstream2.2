package com.freetv.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.freetv.app.databinding.FragmentHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ConcurrentModificationException

private const val GROUP_PREFIX = "group:" // id of a category row is "group:<name>" (RowBuilder)
private const val ALL_ROW_ID = "all"      // id of the "Channels" row, shown when the playlist has no categories

/**
 * The start screen (Phase 20): wordmark + "All channels" button on top, then the hero banner (Phase 22) and rows of channels (HomeAdapter).
 * Rows and hero are built by RowBuilder on Dispatchers.Default every time the shared MainViewModel.channels changes,
 * and again after a favorite changed here or in the player.
 */
class HomeFragment : Fragment() {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    private val vm: MainViewModel by activityViewModels<MainViewModel>()
    private lateinit var adapter: HomeAdapter

    private var buildJob: Job? = null
    private var rowCount = 0
    private var cameFromPlayer = false

    // The rows shown now (the hero's "Watch" plays this channel first, then the items of the first row)
    private var currentRows: List<Row> = emptyList()

    // TV only: true until the focus was put on the hero's Watch button
    private var pendingHeroFocus = false

    // Phase 24: id of the row whose "See all >" opened a category grid; set while that grid covers Home, null otherwise
    private var seeAllRowId: String? = null

    private class Built(val rows: List<Row>, val hero: Channel?)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = HomeAdapter(
            isFavorite = { Prefs.isFavorite(it.url) },
            onPlay = { row, index -> play(row, index) },
            onLong = { ch -> toggleFavorite(ch) },
            onSeeAll = { row -> seeAll(row) },
            onHeroWatch = { ch -> playHero(ch) },
            onHeroList = { ch -> toggleFavorite(ch) }
        )
        b.homeList.layoutManager = LinearLayoutManager(requireContext())
        b.homeList.setHasFixedSize(true)
        b.homeList.adapter = adapter

        b.btnBrowse.setOnClickListener { (activity as? MainActivity)?.openBrowse() }

        pendingHeroFocus = isTv()

        val owner = viewLifecycleOwner
        vm.channels.observe(owner) { rebuild() }
        vm.loading.observe(owner) { isLoading ->
            b.progress.isVisible = isLoading
            updateEmpty()
        }
        // This is the first screen now, so it shows the load / refresh messages the old start screen showed
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
            // Favorites may have changed inside the player: redraw the hearts and rebuild the "My List" row
            adapter.refreshCards()
            rebuild()
        }
    }

    // MainActivity hides Home (show / hide) while a category grid is open, so this runs when BACK closes the grid.
    // Favorites may have been changed in the grid or in the player started from it, so redraw the hearts and the "My List" row,
    // and (TV) put the focus back on the "See all >" that was pressed.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) return
        val id = seeAllRowId ?: return
        seeAllRowId = null
        if (_b == null) return
        adapter.refreshCards()
        rebuild()
        focusSeeAll(id)
    }

    override fun onDestroyView() {
        buildJob?.cancel()
        buildJob = null
        _b = null
        super.onDestroyView()
    }

    /** Builds the rows off the main thread, then hands them to the adapter on the main thread. A newer call replaces an older one. */
    private fun rebuild() {
        val all = vm.channels.value.orEmpty()
        buildJob?.cancel()
        buildJob = viewLifecycleOwner.lifecycleScope.launch {
            val built = withContext(Dispatchers.Default) {
                Built(RowBuilder.build(all, loadFavorites(), emptyList(), false), RowBuilder.pickHero(all))
            }
            val rows = built.rows
            val hero = built.hero
            currentRows = rows
            rowCount = rows.size

            // The hero (when there is one) is the first item, then the rows
            val items = ArrayList<HomeItem>(rows.size + 1)
            if (hero != null) items.add(HomeItem.Hero(hero))
            for (r in rows) items.add(HomeItem.RowItem(r))
            adapter.submitList(items) { focusHeroIfNeeded() }
            updateEmpty()
        }
    }

    // Prefs parses the favorites JSON on first use, so this runs on the background thread.
    // Prefs is not thread-safe: if a toggle on the main thread hits exactly while the list is copied, skip the "My List"
    // row for this pass (every toggle starts a new rebuild right after, which then sees the final state).
    private fun loadFavorites(): List<Channel> =
        try {
            Prefs.favorites()
        } catch (e: ConcurrentModificationException) {
            emptyList()
        }

    // Phase 23: the skeleton is shown while no rows have been submitted yet and something is loading or about to be built
    // (loading, or channels already there but the rows are still being built). It is hidden as soon as rows are submitted.
    private fun updateSkeleton() {
        b.skeleton.root.isVisible = rowCount == 0 && (vm.loading.value == true || !vm.channels.value.isNullOrEmpty())
    }

    private fun updateEmpty() {
        updateSkeleton()
        // never together with the skeleton (the "No channels" text must not flash while the rows are being built)
        b.empty.isVisible = rowCount == 0 && vm.loading.value != true && !b.skeleton.root.isVisible
    }

    private fun play(row: Row, index: Int) {
        if (index !in row.items.indices) return
        ChannelStore.playlist = row.items
        ChannelStore.index = index
        cameFromPlayer = true
        startActivity(Intent(requireContext(), PlayerActivity::class.java))
    }

    // "Watch" on the hero: the hero channel first, then the items of the first row (without the hero channel again)
    private fun playHero(ch: Channel) {
        val first = currentRows.firstOrNull()?.items.orEmpty()
        val list = ArrayList<Channel>(first.size + 1)
        list.add(ch)
        for (c in first) {
            if (c.url != ch.url) list.add(c)
        }
        ChannelStore.playlist = list
        ChannelStore.index = 0
        cameFromPlayer = true
        startActivity(Intent(requireContext(), PlayerActivity::class.java))
    }

    // "See all >" on a row. Category row ("group:<name>"): open that category as a grid above Home (MainActivity.openBrowse(group)).
    // "Channels" row (the playlist has no categories): open the Browse tab, which shows every channel.
    private fun seeAll(row: Row) {
        val main = activity as? MainActivity ?: return
        if (row.id.startsWith(GROUP_PREFIX)) {
            val name = row.id.substring(GROUP_PREFIX.length)
            if (name.isEmpty()) return
            seeAllRowId = row.id
            main.openBrowse(name)
        } else if (row.id == ALL_ROW_ID) {
            main.openBrowse()
        }
    }

    // Focus the "See all >" of that row again. Posted: when onHiddenChanged runs, Home is not visible yet.
    // Does nothing in touch mode (the button is not focusable there) or when the row is no longer on screen.
    private fun focusSeeAll(rowId: String) {
        val list = _b?.homeList ?: return
        list.post {
            val cur = _b ?: return@post
            val pos = adapter.currentList.indexOfFirst { it is HomeItem.RowItem && it.row.id == rowId }
            if (pos >= 0) {
                val vh = cur.homeList.findViewHolderForAdapterPosition(pos) as? HomeAdapter.RowVH
                vh?.b?.rowMore?.requestFocus()
            }
        }
    }

    private fun isTv(): Boolean {
        val pm = requireContext().packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    // TV: when Home first shows its hero, put the focus on "Watch". Runs after the list is committed; the hero holder only exists
    // after the next layout of the list. Does nothing if the user already moved the focus into the list, or if the view is gone.
    private fun focusHeroIfNeeded() {
        val bnd = _b ?: return
        if (!pendingHeroFocus) return
        bnd.homeList.doOnNextLayout {
            val cur = _b
            if (cur != null && pendingHeroFocus && !cur.homeList.hasFocus()) {
                val vh = cur.homeList.findViewHolderForAdapterPosition(0) as? HomeAdapter.HeroVH
                if (vh != null && vh.b.btnWatch.requestFocus()) pendingHeroFocus = false
            }
        }
    }

    private fun toggleFavorite(ch: Channel) {
        val added = Prefs.toggleFavorite(ch)
        Toast.makeText(requireContext(), if (added) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
        adapter.refreshCards() // hearts of this channel in every row
        rebuild()              // the "My List" row
    }
}
