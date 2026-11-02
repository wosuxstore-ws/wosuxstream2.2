# API_MAP (upload this file with every phase; the AI must return it updated)

Project: Wosux Stream (currently still named FreeTV in code). Live TV app, M3U playlists from iptv-org or a custom link.
All code: package `com.freetv.app`. ViewBinding ON. Theme: AppCompat (NO Material components). No Compose.
Paths: `K` = `app/src/main/java/com/freetv/app`, `R` = `app/src/main/res`

## Build facts
- Kotlin 1.9.24, AGP 8.5.2, Gradle 8.7 (installed by the GitHub Actions workflow, no wrapper in repo), Java 17
- compileSdk 34, targetSdk 34, minSdk 21
- namespace `com.freetv.app` (code package, do not change). applicationId `com.wosux.stream`; debug build adds suffix `.test` (installs as `com.wosux.stream.test`)
- Built only by GitHub Actions (Actions tab > Build APK > Run workflow, or by pushing a tag `v*`; pushing to a branch does NOT build). Release is signed with `app/freetv.keystore`.
- CI outputs: `Wosux-Stream.apk` (release) and `Wosux-Stream-test.apk` (debug), uploaded as artifact `Wosux-Stream-apk`; a `v*` tag also attaches both to a GitHub Release.
- Dependencies: core-ktx 1.13.1, appcompat 1.7.0, recyclerview 1.3.2, activity-ktx 1.9.1, fragment-ktx 1.8.2, lifecycle (viewmodel, livedata, runtime) 2.8.4,
  kotlinx-coroutines-android 1.8.1, media3 1.4.1 (exoplayer, exoplayer-hls, exoplayer-dash, ui), coil 2.7.0, core-splashscreen 1.0.1, okhttp 4.12.0, media3-datasource-okhttp 1.4.1

## K/Models.kt
```kotlin
data class Channel(val name: String, val url: String, val logo: String? = null, val group: String? = null,
                   val referrer: String? = null, val userAgent: String? = null)
data class Source(val name: String, val url: String)
object ChannelStore { var playlist: List<Channel>; var index: Int }   // hand-off list from the list screen to PlayerActivity
object Sources { val ALL: Source; val categories: List<Source>; val languages: List<Source>; val countries: List<Source> }
```

## K/Prefs.kt (object; `Prefs.init(ctx)` is called in FreeTVApp.onCreate; SharedPreferences file "freetv")
```kotlin
var source: Source            // selected source, persisted
var customUrl: String
fun favorites(): List<Channel>
fun isFavorite(url: String): Boolean
fun toggleFavorite(c: Channel): Boolean   // true = now a favorite, false = removed
```
Favorites are stored as JSON in key "favs" (fields n, u, l, g, r, a), cached in memory after first load.

## K/M3uParser.kt
```kotlin
object M3uParser { fun parse(reader: BufferedReader): List<Channel> }   // dedupes by url; reads #EXTINF attrs + #EXTVLCOPT referrer/user-agent (no Regex since Phase 13)
```

## K/MainViewModel.kt (AndroidViewModel)
```kotlin
val channels: MutableLiveData<List<Channel>>
val loading: MutableLiveData<Boolean>
val message: MutableLiveData<String?>
val source: MutableLiveData<Source>
var query: String
var favoritesOnly: Boolean
fun setSource(s: Source)
fun load(force: Boolean)   // shows cached playlist first, refreshes if cache older than 6h or force; cache file filesDir/pl_<hash>.m3u (+ ETag sidecar pl_<hash>.m3u.etag)
```
Private: cacheFile(), parseFile(), download(urlStr: String, dest: File): Boolean (OkHttp via Net.client + ETag sidecar; true = new file saved, false = 304 unchanged).

## K/ChannelAdapter.kt
```kotlin
class ChannelAdapter(isFavorite: (Channel) -> Boolean,
                     onClick: (Channel, Int) -> Unit,
                     onLongClick: (Channel, Int) -> Unit,
                     cardWidthPx: Int? = null) : ListAdapter<Channel, ChannelAdapter.VH>   // cardWidthPx added in Phase 16
class VH(val b: ItemChannelBinding) { var boundUrl: String? }   // focus: scales 1.08 in 120 ms (no translationZ); logo via Coil, monogram fallback, heart, dead badge (Phase 16)
```
Phase 19: click / long-click go to `vh.bindingAdapter` (needed because rows share one RecycledViewPool); the monogram radius is computed in `showMono` (see Phase 19 below).

## K/FreeTVApp.kt
`class FreeTVApp : Application, coil.ImageLoaderFactory` : onCreate calls `CrashLog.install(this)`, then `Prefs.init(this)`, then `HealthStore.init(this)` (Phase 10).
`override fun newImageLoader(): ImageLoader` : the app-wide Coil loader (shared `Net.client`, memory cache 25%, disk cache `cacheDir/img` 100 MB,
`respectCacheHeaders(false)`, `crossfade(120)`). Coil calls it once, on the first image request. Details in Phase 08.

## K/MainActivity.kt (host activity since Phase 17; nav bar + tabs since Phase 21)
`class MainActivity : AppCompatActivity`. `onCreate` = `installSplashScreen()` FIRST (before `super.onCreate`), `super.onCreate`, inflate `ActivityMainBinding`, `setContentView`, `CrashLog.showIfAny(this)`
(keep both the splash and CrashLog lines if it is edited again), then the nav setup (see Phase 21 below): 4 `NavItem`s, TV check (nav moved to the top), click listeners, BACK callback, `selectTab(saved tab or Home)`.
`fun openBrowse()` (Phase 21 version): `selectTab(TAB_BROWSE)` + focus on the Browse nav item (no replace, no back stack any more). Called by `HomeFragment.btnBrowse` and by the "See all" of the `all` row.
`fun openBrowse(group: String?)` (Phase 24, overload): pushes `BrowseFragment.newInstance(group)` onto the back stack ABOVE the current tab (current tab hidden, grid added with tag + back stack name `group_browse`). Called by `HomeFragment` for rows `group:<name>`. See Phase 24 below.
Private: `selectTab(tab: Int)` (Phase 24: first pops a category grid that is on the back stack), `newTab(tab: Int): Fragment`, `updateNav()`, `isTv(): Boolean`, `backToHome: OnBackPressedCallback`, `current: Int`, `items: List<NavItem>`; `onSaveInstanceState` stores the tab under key "tab".
Tab constants (private companion): `TAB_HOME = 0`, `TAB_BROWSE = 1` (My List = 2, Settings = 3 are the placeholder tabs), tags `tab_home`, `tab_browse`, `tab_list`, `tab_settings`.

## K/ComingSoonFragment.kt (Phase 21)
`class ComingSoonFragment : Fragment(R.layout.fragment_coming_soon)`, no arguments, no logic. Used for the My List and Settings tabs until later phases replace them.

## K/BrowseFragment.kt (grid screen, moved out of MainActivity in Phase 17)
`class BrowseFragment : Fragment()`, no constructor arguments (use `BrowseFragment()` for the Browse tab, `BrowseFragment.newInstance(group: String?)` since Phase 24 for a one-category grid: `companion object { fun newInstance(group: String?): BrowseFragment }`, argument key "group"). `vm: MainViewModel by activityViewModels<MainViewModel>()` (same instance the activity used to own), binding `FragmentBrowseBinding` in `_b` (set in onCreateView, nulled in onDestroyView).
Grid of channels (span = screen dp / 150, clamp 2..10), buttons Source / Search / Favorites / Refresh, dialogs (source menu, country/category/language lists,
search text, custom M3U link). Click = set ChannelStore.playlist/index then start PlayerActivity. onResume after player: refresh favorites + restore focus.
Phase 24 adds private `group: String?`, `groupJob`, `groupReady`, `favOnly`, `onCreate`, `applyGroup(name)`, `filterGroup(all, name)`, `inGroup(c, name)`; details in Phase 24 below.
Private members (same names and logic as the old MainActivity): `adapter`, `visible`, `cameFromPlayer`, `applyFilter()`, `play(position)`, `toggleFavorite(ch, pos)`, `showSourceMenu()`, `pickSource(titleRes, list)`, `showSearch()`, `promptCustomUrl()`, `promptText(...)`.
LiveData observers use `viewLifecycleOwner`; dialogs and toasts use `requireContext()`.

## K/HomeModels.kt (Phase 18, pure Kotlin)
`data class Row(val id: String, val title: String, val items: List<Channel>, val total: Int)`
`sealed interface HomeItem { data class Hero(val channel: Channel) : HomeItem; data class RowItem(val row: Row) : HomeItem }`

## K/HomeAdapter.kt (Phase 19; used by HomeFragment since Phase 20; draws the hero since Phase 22)
```kotlin
class HomeAdapter(isFavorite: (Channel) -> Boolean,
                  onPlay: (Row, Int) -> Unit,        // card clicked: (row, index in row.items)
                  onLong: (Channel) -> Unit,         // card long-pressed / OK held
                  onSeeAll: (Row) -> Unit,
                  onHeroWatch: (Channel) -> Unit = { },   // Phase 22: hero "Watch" clicked
                  onHeroList: (Channel) -> Unit = { }) : ListAdapter<HomeItem, RecyclerView.ViewHolder>   // Phase 22: hero "My List" clicked
class HomeAdapter.RowVH(val b: ItemRowBinding, val lm: LinearLayoutManager) { lateinit var inner: ChannelAdapter; var row: Row?; var epoch: Int }
class HomeAdapter.HeroVH(val b: ItemHeroBinding) { var channel: Channel?; var boundUrl: String? }   // Phase 22
fun HomeAdapter.refreshCards()   // Phase 20: redraw the cards (hearts, dead badge) of every row without rebuilding the rows
```
`HomeItem.RowItem` is drawn as a row (Phase 19); `HomeItem.Hero` is drawn as the hero banner (Phase 22; it was a zero-height holder before). Details in Phase 19 and Phase 22 below.

## K/HomeFragment.kt (Phase 20, the start screen)
`class HomeFragment : Fragment()`, no constructor arguments. `vm: MainViewModel by activityViewModels<MainViewModel>()` (same instance as BrowseFragment), binding `FragmentHomeBinding` in `_b` (set in onCreateView, nulled in onDestroyView).
Private members: `adapter: HomeAdapter`, `buildJob: Job?`, `rowCount`, `cameFromPlayer`, `rebuild()`, `loadFavorites()`, `updateEmpty()`, `play(row, index)`, `toggleFavorite(ch)`; Phase 23 adds `updateSkeleton()`; Phase 22 adds `currentRows`, `pendingHeroFocus`, `playHero(ch)`, `isTv()`, `focusHeroIfNeeded()`, private class `Built(rows, hero)`. Phase 24 adds `seeAllRowId`, `seeAll(row)`, `focusSeeAll(rowId)`, `override onHiddenChanged(hidden)` and two private top-level consts `GROUP_PREFIX`, `ALL_ROW_ID` in HomeFragment.kt. Details in Phase 20, Phase 22, Phase 23 and Phase 24 below.

## K/ShimmerView.kt (Phase 23)
```kotlin
class ShimmerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : View   // use in XML as <com.freetv.app.ShimmerView>
```
Skeleton block: rounded rect (radius `@dimen/card_radius`) with a sweeping highlight (`surface` -> `surface_focus` -> `surface`). Size from the layout, no custom attributes. Animates only while attached + shown + window visible. Details in Phase 23 below.

## K/RowBuilder.kt (Phase 18, pure Kotlin, safe on Dispatchers.Default)
`object RowBuilder`
`fun build(all: List<Channel>, favorites: List<Channel>, recents: List<Channel>, hideDead: Boolean): List<Row>` : see Phase 18 below for the exact rules and row ids (`recents`, `favs`, `group:<name>`, `all`).
`fun pickHero(all: List<Channel>): Channel?` : first channel with a non-blank logo that is not DEAD, or null.

## K/PlayerActivity.kt (full-screen live player)
ExoPlayer (Media3) created in onStart, released in onStop. `playCurrent()`, `zap(delta)`, overlay auto-hide 5 s, one retry on error,
BEHIND_LIVE_WINDOW handling, remote keys (UP/CH+ next, DOWN/CH- previous, OK toggles info, LEFT/RIGHT show overlay), hides system bars, keeps screen on.
Reads ChannelStore.playlist/index. Data source: `OkHttpDataSource.Factory(Net.client)` with the channel's own user-agent (fallback `DEFAULT_UA`, a Chrome-on-Android string) and `Referer` (Phase 09).
The player is built with a custom `DefaultLoadControl`, `DefaultRenderersFactory` (decoder fallback), audio focus and pause-on-unplug (Phase 09).
Since Phase 12 the player reports to `HealthStore` (READY = alive, final error = failed) and `zap(delta)` skips channels that are DEAD (`SKIP_DEAD`).

## Layout ids
- activity_main (Phase 21): root vertical LinearLayout (`@color/bg`) = `container` (FragmentContainerView, height 0dp weight 1) + `<include id=nav layout=view_nav>`   -> `ActivityMainBinding` (`root`: LinearLayout, `container`, `nav`: `ViewNavBinding`)
- view_nav (Phase 21): see Phase 21 below; ids navHome / navHomeIcon / navHomeLabel, navBrowse / navBrowseIcon / navBrowseLabel, navList / navListIcon / navListLabel, navSettings / navSettingsIcon / navSettingsLabel   -> `ViewNavBinding`
- fragment_coming_soon (Phase 21): FrameLayout with one centered TextView (no ids)
- fragment_browse: progress, count, btnSource, btnSearch, btnFav, btnRefresh, list, empty   (this is the old activity_main content) + Phase 24: groupBar (LinearLayout, gone), btnBack (Button), groupTitle (TextView), appTitle (TextView, the old app-name title), toolbar (HorizontalScrollView holding the 4 buttons)   -> `FragmentBrowseBinding`
- item_channel: logo, mono, health, star, name   (star is an ImageView since Phase 16; mono / health since Phase 15)
- item_row (Phase 19): rowTitle (TextView), rowMore (TextView, focusable), rowList (RecyclerView)   -> `ItemRowBinding`
- fragment_home (Phase 20): progress (ProgressBar), btnBrowse (Button), homeList (RecyclerView), skeleton (`<include>` of view_home_skeleton, Phase 23; `b.skeleton.root` = its root LinearLayout), empty (TextView)   -> `FragmentHomeBinding` (the wordmark TextViews have no ids)
- view_home_skeleton (Phase 23): root vertical LinearLayout, no ids (16 `ShimmerView`s)   -> `ViewHomeSkeletonBinding`
- item_hero (Phase 22): heroName (TextView), heroCategory (TextView, gone when the channel has no category), btnWatch (Button), btnHeroList (Button), heroLogo (ImageView), heroMono (TextView, gone)   -> `ItemHeroBinding` (the LIVE chip and containers have no ids)
- activity_player: playerView, touchLayer, buffering, error, overlay, chName, chInfo, btnPrev, btnFav, btnNext

## Resources
- dimens (besides Phase 14): hero_plate_w, hero_plate_h (Phase 22; `values/dimens_hero.xml` = 88dp x 88dp, `values-w600dp/dimens_hero.xml` = 240dp x 135dp)
- colors: bg, surface, surface_focus, accent, text, text_dim, logo_bg, brand_blue, brand_blue_mid, brand_cyan, silver, silver_dim, live_red, ok_green, dead_gray, scrim, ic_launcher_background
- styles: Theme.FreeTV (AppCompat.NoActionBar), Theme.FreeTV.Player, Theme.Wosux.Starting (parent Theme.SplashScreen; splash theme of MainActivity), Btn
- drawables: bg_button, bg_card, bg_logo, bg_overlay, bg_dot, ratio_16_9, ic_tv, ic_heart, banner (drawable-xhdpi), bg_nav_item, ic_nav_home, ic_nav_browse, ic_nav_list, ic_nav_settings (Phase 21), bg_hero, bg_button_primary, bg_live (Phase 22)
- mipmaps: ic_launcher (PNG in mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi + adaptive XML in mipmap-anydpi-v26), ic_launcher_foreground (PNG in the same five density folders; also the splash icon),
  ic_launcher_round (adaptive XML in mipmap-anydpi-v26 only)
- strings: app_name, source_btn, search, search_active, favorites_off, favorites_on, refresh, channels_count, empty_list, empty_favorites,
  fav_added, fav_removed, choose_source, src_all, src_country, src_category, src_language, src_custom, search_title, search_hint,
  custom_hint, custom_name, custom_invalid, load, clear, err_empty, err_refresh, err_load, prev, next, fav_off, fav_on, player_error, nav_home, nav_browse, nav_list, nav_settings, coming_soon (Phase 21), hero_live, hero_watch, hero_list, hero_in_list (Phase 22), back, empty_group (Phase 24)

## Manifest
Permissions INTERNET, ACCESS_NETWORK_STATE. Leanback + touchscreen not required. usesCleartextTraffic=true. App class .FreeTVApp, theme Theme.FreeTV, icon @mipmap/ic_launcher, roundIcon @mipmap/ic_launcher_round, TV banner @drawable/banner.
MainActivity = LAUNCHER + LEANBACK_LAUNCHER, theme Theme.Wosux.Starting (splash, switches to Theme.FreeTV after launch). PlayerActivity = sensorLandscape, theme Theme.FreeTV.Player, not exported.

## Added by phases (the AI appends below)

### Phase 01: CrashLog
- `K/CrashLog.kt`, `object CrashLog`:
  - `fun install(app: Application)`: sets the default uncaught-exception handler; writes device, SDK, versionName, thread and stack trace to `filesDir/last_crash.txt`, then calls the previous handler.
  - `fun pending(ctx: Context): String?`: saved crash text or null.
  - `fun clear(ctx: Context)`: deletes the saved crash.
  - `fun showIfAny(activity: AppCompatActivity)`: dialog "Wosux Stream crashed last time" (first 4000 chars, selectable); "Copy" copies the FULL text and keeps the dialog open; "Close" deletes the file.
- Hooks: `FreeTVApp.onCreate` -> `CrashLog.install(this)` first; `MainActivity.onCreate` -> `CrashLog.showIfAny(this)` after setContentView.
- Limitation: a crash inside Application.onCreate repeats before the dialog can appear.

### Phase 02: Brand colors
- `R/values/colors.xml` values changed: bg #0A0D12, surface #131923, surface_focus #1C2533, accent #02E1FE (cyan, was yellow), text #FFFFFF, text_dim #9AA4B2, logo_bg #E9ECF2.
- New colors: brand_blue #0088FF, brand_blue_mid #02AEF9, brand_cyan #02E1FE, silver #E2E3E9, silver_dim #9AA0AA, live_red #FF4D5A, ok_green #2EE59D, dead_gray #5B6475, scrim #CC0A0D12.
- Still hard-coded elsewhere (to be replaced in later phases): item_channel.xml star color #F59E0B, activity_player.xml error background #AA000000, bg_overlay.xml gradient.

### Phase 03: App name, applicationId, CI file names
- `app/build.gradle.kts`: `applicationId` = `"com.wosux.stream"` (was `com.freetv.app`). `namespace` stays `"com.freetv.app"`. Signing config `freetv`, `app/freetv.keystore` and the debug `applicationIdSuffix = ".test"` are unchanged.
- `R/values/strings.xml`: `app_name` = `Wosux Stream` (was `FreeTV`). The launcher label comes from `@string/app_name` in the manifest.
- `.github/workflows/build-apk.yml`: `on:` is now only `push` with `tags: [ 'v*' ]` plus `workflow_dispatch:` (the branch-push trigger was removed). `cp` lines, the artifact (`Wosux-Stream-apk`) and the release `files:` list use `Wosux-Stream.apk` and `Wosux-Stream-test.apk`. Nothing else in the workflow changed.
- New applicationId = new app on the device: the old FreeTV must be uninstalled by hand and its favorites do not carry over.
- Still says FreeTV (left alone on purpose, not part of this phase): class `FreeTVApp`, styles `Theme.FreeTV` / `Theme.FreeTV.Player`, `rootProject.name` in `settings.gradle.kts`, the `.keystore` file name, and the text in `README.md` (updated in Phase 40).

### Phase 04: Launcher icon + TV banner
- New `R/mipmap-anydpi-v26/ic_launcher.xml` and `R/mipmap-anydpi-v26/ic_launcher_round.xml`: identical `<adaptive-icon>` files, `<background android:drawable="@color/ic_launcher_background" />` and `<foreground android:drawable="@mipmap/ic_launcher_foreground" />`. Android 8.0+ (API 26+) uses these; older Android uses the PNG `ic_launcher` from the density folders.
- New `R/values/ic_launcher_background.xml`: color `ic_launcher_background` = `#0A0D12` (same value as `bg`, separate name on purpose for the launcher).
- `app/src/main/AndroidManifest.xml`: `<application>` gained `android:roundIcon="@mipmap/ic_launcher_round"`. Nothing else changed (`android:icon="@mipmap/ic_launcher"` and `android:banner="@drawable/banner"` were already there).
- Art files made and placed by the user, not by the AI (sizes as the Phase 04 plan specifies, not checked by the AI): `ic_launcher.png` (48/72/96/144/192 px) and `ic_launcher_foreground.png` (108/162/216/324/432 px) in `R/mipmap-mdpi`, `-hdpi`, `-xhdpi`, `-xxhdpi`, `-xxxhdpi`, and the new TV banner `R/drawable-xhdpi/banner.png` (same name as before, so no manifest change was needed for it).
- Resource names now in use: mipmap `ic_launcher`, `ic_launcher_round`, `ic_launcher_foreground`; color `ic_launcher_background`. Phase 05 (splash) may reference `@mipmap/ic_launcher_foreground`.
- Limitation: `ic_launcher_round` exists only in `mipmap-anydpi-v26` (no round PNGs in the density folders), so it resolves on Android 8.0+ only.

### Phase 05: Splash screen
- `app/build.gradle.kts`: new dependency `implementation("androidx.core:core-splashscreen:1.0.1")` (the only new library; no other version changed).
- `R/values/themes.xml`: new style `Theme.Wosux.Starting` (parent `Theme.SplashScreen`, supplied by the library): `windowSplashScreenBackground` = `@color/bg`, `windowSplashScreenAnimatedIcon` = `@mipmap/ic_launcher_foreground`, `postSplashScreenTheme` = `@style/Theme.FreeTV`. Existing styles `Theme.FreeTV`, `Theme.FreeTV.Player` and `Btn` are untouched.
- `app/src/main/AndroidManifest.xml`: `MainActivity` now has `android:theme="@style/Theme.Wosux.Starting"`. The `<application>` theme stays `Theme.FreeTV`; `PlayerActivity` keeps `Theme.FreeTV.Player`.
- `K/MainActivity.kt`: new import `androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen`; `onCreate` now begins with `installSplashScreen()` BEFORE `super.onCreate(savedInstanceState)`. Every other line is unchanged (including `CrashLog.showIfAny(this)` after setContentView).
- No `setKeepOnScreenCondition` and no exit animation: the splash goes away as soon as MainActivity draws its first frame.
- Rule for later phases: `installSplashScreen()` must stay the first call in `MainActivity.onCreate`, before `super.onCreate`, wherever that activity is edited (Phase 17 keeps it). Other activities do not use the splash theme.
- Needs `@mipmap/ic_launcher_foreground` (user-supplied art from Phase 04); the build fails if that PNG is missing.

### Phase 06: Net.kt (one shared network client)
- `app/build.gradle.kts`: new dependencies `implementation("com.squareup.okhttp3:okhttp:4.12.0")` and `implementation("androidx.media3:media3-datasource-okhttp:1.4.1")`. No other version changed.
- New `K/Net.kt`, `object Net`:
  - `const val UA = "WosuxStream/1.0 (Android)"`
  - `val client: OkHttpClient` (lazy): connect 10 s, read 20 s, retryOnConnectionFailure true, followRedirects + followSslRedirects true, `Dispatcher` maxRequests 64 / maxRequestsPerHost 16, application interceptor that sets `User-Agent = UA` only when the request has no User-Agent header. No `Cache` (live segments must not fill the disk).
  - `val probe: OkHttpClient` (lazy) = `client.newBuilder()` with connect 4 s, read 4 s, callTimeout 6 s. Shares the connection pool and dispatcher of `client`.
- In Phase 06 `Net` was not used anywhere yet. Since Phase 07 `MainViewModel.download()` uses `Net.client`; since Phase 08 Coil (through `FreeTVApp.newImageLoader()`) uses it too; since Phase 09 so does the player (`OkHttpDataSource.Factory(Net.client)` in PlayerActivity); `Net.probe` is first used in Phase 11.

### Phase 07: Playlist download through Net, with ETag
- Only `K/MainViewModel.kt` changed (plus this file). Its public API is unchanged (`channels`, `loading`, `message`, `source`, `query`, `favoritesOnly`, `setSource`, `load(force)`). `Net.kt` and `app/build.gradle.kts` are untouched; no new library, no new resource, no new string.
- Imports in MainViewModel: removed `java.net.HttpURLConnection` and `java.net.URL`, added `okhttp3.Request`.
- `private fun download(urlStr: String, dest: File): Boolean` (it returned nothing before) now uses `Net.client` (OkHttp). It still runs only inside `withContext(Dispatchers.IO)`. OkHttp follows the redirects itself (the manual 5-redirect loop is gone) and `Net` adds the `User-Agent` (`Net.UA`; was `FreeTV/1.0 (Android)`). Timeouts are now the ones of `Net.client` (connect 10 s, read 20 s; were 15 s / 30 s).
  - ETag sidecar: `dest.name + ".etag"` in the same folder (`filesDir/pl_<hash>.m3u.etag`), holds the raw `ETag` header text. `If-None-Match` is sent only when the sidecar is readable (printable ASCII) AND `dest` still exists and is not empty, so a 304 can never leave the app without a playlist file.
  - HTTP 304 (only possible when `If-None-Match` was sent): `dest.setLastModified(now)` (restarts the 6 h stale timer), nothing is written, returns `false` = unchanged.
  - HTTP 200: the body is written to `dest.name + ".tmp"`. An empty file deletes the tmp and throws `IOException("Empty playlist")` (as before; old cache and old ETag stay untouched). Otherwise the old sidecar is deleted, the tmp is renamed over `dest` (copy fallback as before), then the new `ETag` header (when the server sent one) is saved to the sidecar. Returns `true`. A kill between the rename and the sidecar write only means "no ETag", so the next refresh downloads in full.
  - Any other status throws `IOException("HTTP <code>")` (as before).
- `load()`: only the lines that call `download()` changed. The cached list is still parsed and shown first. After the download, the file is parsed and `channels` is emitted only when `download()` returned `true` OR nothing was shown from the cache (`shown == false`). If it returned `false` (304) and the cached list is already on screen, nothing is re-parsed and nothing is re-emitted. An empty parse result still sets the `err_empty` message; the catch blocks (`err_refresh` / `err_load`) are unchanged.
- Unchanged on purpose: `setSource()`, `cacheFile()`, `parseFile()`, `STALE_MS` (6 h), the `.tmp` file name and every field.
- Limitations: a server that sends no ETag is downloaded in full on every refresh (same as before Phase 07). There is no OkHttp `Cache` on purpose, so the sidecar file is the only revalidation. Cancelling a `load()` does not stop its blocking download, and two overlapping downloads of the same source still share the same `.tmp` name (existing behaviour, not touched in this phase).

### Phase 08: Fast image loader (logos)
- Only `K/FreeTVApp.kt` changed (plus this file). `Net.kt`, `app/build.gradle.kts`, every layout/resource and every other Kotlin file are untouched. No new library (Coil 2.7.0 and OkHttp 4.12.0 were already there), no new resource, no new string.
- `class FreeTVApp : Application(), ImageLoaderFactory`. New imports: `coil.ImageLoader`, `coil.ImageLoaderFactory`, `coil.disk.DiskCache`, `coil.memory.MemoryCache`, `java.io.File`. `onCreate` is unchanged (`super.onCreate()`, `CrashLog.install(this)`, `Prefs.init(this)`, in that order).
- New `override fun newImageLoader(): ImageLoader` = `ImageLoader.Builder(this)` with:
  - `okHttpClient { Net.client }`: the shared client from Phase 06 (no OkHttp `Cache`; its `User-Agent` interceptor also applies to logo requests). The lambda overload is used on purpose: Coil then creates the client lazily on its background thread when the first logo is downloaded, so this code never builds `Net.client` on the main thread.
  - `memoryCache { MemoryCache.Builder(this@FreeTVApp).maxSizePercent(0.25).build() }`: 25% of the app's available memory.
  - `diskCache { DiskCache.Builder().directory(File(cacheDir, "img")).maxSizeBytes(100L * 1024 * 1024).build() }`: folder `cacheDir/img`, 100 MB, least recently used entries are removed first. It lives in the cache dir, so Android's "Clear cache" empties it.
  - `respectCacheHeaders(false)`: a logo that is in the disk cache is used as is, without asking the server again, whatever the server's cache headers say. This is what makes a logo that was seen once show instantly, also offline.
  - `crossfade(120)`: 120 ms fade-in. Coil skips the fade for images found in the memory cache.
- Coil calls `newImageLoader()` once, on the first image request, and keeps the result as the app-wide default loader (`ImageView.load(...)`, `Coil.imageLoader(context)`). `ChannelAdapter` (`logo.load(url) { placeholder(R.drawable.ic_tv); error(R.drawable.ic_tv) }`) is NOT changed and now uses it. Later screens (hero banner, rows) that call `.load(...)` get the same loader automatically: do not create another `ImageLoader`.
- Limitations: because cache headers are ignored, a channel that replaces its logo file under the same URL keeps showing the old picture until it is evicted (100 MB, least recently used first) or the cache is cleared. A logo is only available offline after it was downloaded once.

### Phase 09: Fast-start player
- Only `K/PlayerActivity.kt` changed (plus this file). `Net.kt`, `app/build.gradle.kts` (`media3-datasource-okhttp:1.4.1` and `okhttp:4.12.0` came in Phase 06), layouts, resources and every other Kotlin file are untouched. No new library, no new resource, no new string.
- Imports: removed `androidx.media3.datasource.DefaultHttpDataSource`; added `androidx.media3.common.AudioAttributes`, `androidx.media3.datasource.okhttp.OkHttpDataSource`, `androidx.media3.exoplayer.DefaultLoadControl`, `androidx.media3.exoplayer.DefaultRenderersFactory`.
- `onStart()`: the player is now built with `ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))`, then `.setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15000, 40000, 1500, 3000).build())`, `.setHandleAudioBecomingNoisy(true)`, `.setAudioAttributes(AudioAttributes.DEFAULT, true)`, `.build()`.
  - Buffer durations in ms: min 15000, max 40000, start playback after 1500, restart after a rebuffer after 3000 (ExoPlayer defaults are 50000 / 50000 / 2500 / 5000), so a channel starts and recovers sooner.
  - `setEnableDecoderFallback(true)`: if a decoder fails to start, ExoPlayer tries the next decoder the device offers.
  - `setHandleAudioBecomingNoisy(true)`: playback pauses (`playWhenReady = false`) when headphones are unplugged or a Bluetooth audio device disconnects. The screen has no play button yet: resume with the Play/Pause key of the remote or headset, or by zapping (`playCurrent()` sets `playWhenReady = true`).
  - `setAudioAttributes(AudioAttributes.DEFAULT, true)`: media usage; the player takes audio focus and reacts when another app takes it (pauses or lowers the volume).
- `playCurrent()`: `DefaultHttpDataSource.Factory()` is replaced by `OkHttpDataSource.Factory(Net.client)` with `.setUserAgent(ch.userAgent ?: DEFAULT_UA)` (same rule as before: the channel's own user-agent, else `DEFAULT_UA`) and, when the channel has a referrer, `setDefaultRequestProperties(mapOf("Referer" to it))` (unchanged). The factory still goes to `DefaultMediaSourceFactory(this).setDataSourceFactory(http)`. The .m3u8 / .mpd MIME hints, `setMediaSource`, `prepare()` and `playWhenReady = true` are unchanged.
- Removed three calls that only `DefaultHttpDataSource.Factory` has: `setAllowCrossProtocolRedirects(true)`, `setConnectTimeoutMs(15_000)`, `setReadTimeoutMs(20_000)`. `Net.client` takes over: it follows redirects including http <-> https (`followRedirects` / `followSslRedirects` true), connect timeout 10 s (was 15 s), read timeout 20 s (same). The player uses `Net.client`, never `Net.probe` (its call timeout would cut live streams).
- Unchanged on purpose: `zap()`, the overlay and its 5 s auto-hide, the one quiet retry, BEHIND_LIVE_WINDOW handling, remote key handling, `onStop()` releasing the player.
- Limitations: no OkHttp `Cache` (on purpose, see Phase 06). The player's requests share the dispatcher of `Net.client` (64 requests in total, 16 per host) with playlist downloads and logo downloads.

### Phase 10: HealthStore (remember which channels work)
- New `K/HealthStore.kt`, `object HealthStore`. Changed: `K/FreeTVApp.kt` (one line, see below). No layout, resource, string, library or version change. Nothing in the UI uses it yet (`StreamChecker` = Phase 11, player reporting = Phase 12).
- Public API:
```kotlin
enum class State { UNKNOWN, ALIVE, DEAD }                    // HealthStore.State
val version: kotlinx.coroutines.flow.MutableStateFlow<Int>   // starts at 0; +1 (at most once per second) when some channel's State changed
fun init(ctx: Context)               // once, from FreeTVApp.onCreate; later calls do nothing; loads filesDir/health.txt on Dispatchers.IO
fun state(url: String): State        // UNKNOWN until the file is loaded
fun isFresh(url: String): Boolean    // state(url) != UNKNOWN (non-expired ALIVE or DEAD result)
fun mark(url: String, alive: Boolean)// record a check result; callable from any thread; debounced save
```
- Rules: key = `url.hashCode()` (a rare hash collision makes two urls share one entry). ALIVE counts for 6 h after the last success. DEAD needs 2 or more failures in a row (fail counter) and counts for 24 h after the last failure. One failure = UNKNOWN (so `isFresh` is false and it should be checked again). After expiry the entry is UNKNOWN but its fail counter stays, so one more failure right after a long DEAD period makes it DEAD again. `mark(url, true)` resets the counter to 0, `mark(url, false)` adds 1. A system clock set back makes entries UNKNOWN.
- `version` is raised only when the State of the marked channel really changes (not on every mark), plus once after the file finished loading (if it had entries). The first change goes out at once, further changes within 1 s are merged into one delayed bump, so the last change is always published.
- Saving: every `mark` restarts a 3 s timer; when it runs (background thread) the whole map is written to `filesDir/health.txt.tmp` and renamed over `health.txt`. Nothing is saved before the load has finished (it would overwrite the file). Marks made while loading are kept and win over the file. Save errors are swallowed and retried on the next `mark`.
- File format `filesDir/health.txt`: one line per entry `hash,alive,fails,timestamp` (`alive` 0/1, timestamp = `System.currentTimeMillis()`), oldest first. Bad lines are skipped. Cap 20,000 entries; when over the cap the entry with the oldest last update is dropped.
- Threads: all state is behind locks; `state()`, `isFresh()`, `mark()` are cheap (map lookup) and safe on the main thread. Disk work only on `Dispatchers.IO` (private scope, `SupervisorJob`).
- `K/FreeTVApp.kt`: `onCreate` is now `super.onCreate()`, `CrashLog.install(this)`, `Prefs.init(this)`, `HealthStore.init(this)`. `newImageLoader()` is unchanged.
- Limitations: while checks keep running with pauses shorter than 3 s, nothing is written until they pause (a kill in that time loses those results). Nothing saves on app exit. Callers should not mark channels as failed while the device is offline (two offline checks make a channel DEAD for 24 h).

### Phase 11: StreamChecker (test a channel without playing it)
- New `K/StreamChecker.kt`, `object StreamChecker`. Nothing else changed: no layout, resource, string, library, version, manifest or other Kotlin file. Nothing calls it yet (`MainViewModel.checkAhead` = Phase 29, player reporting = Phase 12). First user of `Net.probe`.
- Public API:
```kotlin
suspend fun check(ch: Channel): Boolean                      // true = the url answers like a stream; any exception = false; CancellationException is rethrown
suspend fun checkAll(list: List<Channel>, limit: Int = 40)   // returns Unit; results go to HealthStore.mark
```
- Imports used: kotlinx.coroutines (`CancellationException`, `Dispatchers`, `ensureActive`, `launch`, `suspendCancellableCoroutine`, `withContext`), `kotlinx.coroutines.sync.Semaphore`, `kotlin.coroutines.resume`, `okhttp3.Call`, `okhttp3.Request`, `okhttp3.Response`. Needs no new dependency.
- `check(ch)`: runs on `Dispatchers.IO` with `Net.probe` (connect 4 s, read 4 s, whole call 6 s). A GET request is built with `User-Agent` = `ch.userAgent`, or (when null/blank) a private `DEFAULT_UA` (the same Chrome-on-Android string as `PlayerActivity.DEFAULT_UA`, copied because that one is private, so the check sees what the player would see), and `Referer` = `ch.referrer` when not null/blank.
  - URL contains `.m3u8` (case-insensitive): plain GET, success only if HTTP 200 and the first 1024 bytes (read in a loop until 1024 bytes or end of stream) contain `#EXTM3U` (case-insensitive).
  - any other URL: GET with `Range: bytes=0-1023`, success if HTTP 200 or 206 and the `Content-Type` header is missing or does not contain `text/html` (case-insensitive). The body is not read.
  - The response is always closed (`use`), after at most 1024 bytes were read. Cancelling the coroutine calls `Call.cancel()`, so a blocked read ends at once.
  - Invalid URL or invalid header text (`IllegalArgumentException`), network errors, timeouts, anything else = `false`.
- `checkAll(list, limit = 40)`: runs on `Dispatchers.IO`. Walks the list in order; skips a url already seen in this call (duplicates are checked once) and every channel where `HealthStore.isFresh(url)` is true; stops after `limit` real checks have been started (`limit <= 0` or an empty list does nothing). At most 6 checks run at the same time (`Semaphore(6)`, acquired before each check starts). Each finished check calls `HealthStore.mark(url, result)`. Returns when all started checks are finished. If the caller's coroutine is cancelled, running checks are cancelled and their results are NOT marked.
- Limitations: one failed check is not yet a result (`HealthStore` needs 2 failures in a row for DEAD), so a channel with one failure is checked again by the next `checkAll`. `checkAll` does not look at the network state: callers should not run it while the device is offline (two offline checks make a channel DEAD for 24 h). A server that answers 200 with a playlist-less body to the `.m3u8` GET, or that blocks the app's requests, counts as dead. For a non-`.m3u8` url only the status code and the content type are tested, not the content.

### Phase 12: Player reports health, zapping skips dead channels
- Only `K/PlayerActivity.kt` changed (plus this file). `HealthStore.kt`, `StreamChecker.kt`, `app/build.gradle.kts`, layouts, resources, strings, manifest and every other Kotlin file are untouched. No new library, no new import (`HealthStore` is in the same package), no new resource, no new string. No new public API.
- New private members of `PlayerActivity`:
  - `private var healthMarked = false`: true once `HealthStore.mark(url, true)` was sent for the current `prepare()`. Reset to `false` in every place that calls `prepare()`: `playCurrent()`, `retryTask` (the one quiet retry) and the BEHIND_LIVE_WINDOW branch of `onPlayerError`.
  - `private companion object { const val SKIP_DEAD = true }` (next to `DEFAULT_UA`): `false` turns the skipping off, zap then behaves exactly as before Phase 12.
- Reporting (both on the main thread; `HealthStore.mark` is only a map update, the file is saved later on `Dispatchers.IO`). The url is that of `ChannelStore.playlist[index]`, the channel being played:
  - `onPlaybackStateChanged(STATE_READY)`: `HealthStore.mark(url, true)` the first time READY is reached after a `prepare()` (`healthMarked` stops repeat marks on every rebuffer). Also still hides the error text, as before.
  - `onPlayerError`, FINAL error only (the branch after the existing single retry, `retried == true`): `HealthStore.mark(url, false)`, then the error text / overlay as before. The first error (quiet retry) and BEHIND_LIVE_WINDOW errors do NOT mark anything.
- `zap(delta: Int)` (private; called by btnPrev / btnNext and the remote keys, signature unchanged):
  - `target` = the plain neighbour `((index + delta) % n + n) % n` (what Phase 11 did).
  - If `SKIP_DEAD`: starting at that neighbour and walking in the zapping direction (wrapping around the list), at most `n` (= playlist.size) candidates are looked at; the first one with `HealthStore.state(url) != HealthStore.State.DEAD` becomes `target`. If every candidate is DEAD, `target` stays the plain neighbour (= old behaviour).
  - Then `index = target; playCurrent()` (unchanged). Empty playlist: returns, as before.
  - The `n` candidates end with the current channel itself, so when every OTHER channel is DEAD and the current one is not, zapping restarts the current channel (the same thing a one-channel list always did).
  - Cost: up to `n` cheap `HealthStore.state` calls on the main thread only when most channels are DEAD (a stand-in test with 20,000 all-DEAD channels took about 4 ms on a desktop JVM).
- Effect on `HealthStore` rules (Phase 10): one failed final error = UNKNOWN (zap does not skip yet); the 2nd final error in a row for the same url = DEAD for 24 h (zap skips it); the next READY (`mark(url, true)`) makes it ALIVE and clears the counter. `HealthStore.state` is UNKNOWN until `health.txt` has loaded, so nothing is skipped in the first moments after app start.
- Not changed on purpose: `retried` is still reset only in `playCurrent()` (a channel that played for a long time and then errors twice counts the second error as FINAL, as before). Selecting a channel from the grid still plays it even if it is DEAD (only zapping skips). `MainActivity` / `ChannelAdapter` do not show health yet.
- Limitations: a final error while the device is OFFLINE is marked like any other failure (the player does not look at the network state): zapping through channels with the network down can mark them failed, and two such errors on the same channel make it DEAD (skipped by zapping) for 24 h. A later phase should skip the marking when offline. A final error caused by the device (e.g. decoder problem) is also marked as a channel failure. A stale player event arriving right after a zap could be booked to the new channel (not guarded; ExoPlayer clears errors on `prepare()`).

### Phase 13: Faster M3U parser
- Only `K/M3uParser.kt` changed (plus this file). `Models.kt`, `MainViewModel.kt` (its call `M3uParser.parse(it)` is untouched), `app/build.gradle.kts`, layouts, resources, strings, manifest and every other Kotlin file are untouched. No new library, no new import (still only `java.io.BufferedReader`), no new resource, no new string.
- Public API is unchanged: `object M3uParser { fun parse(reader: BufferedReader): List<Channel> }`. New members are all `private`:
  - `private const val EXPECTED_CHANNELS = 12_000` (initial capacity of the result `ArrayList`) and `private const val EXPECTED_URLS = 16_384` (initial capacity of the dedupe `HashSet`, resizes only above 12,288 entries). Starting sizes only: a bigger playlist still grows normally, a small one wastes about 100 KB for a moment.
  - `private class Attrs` (one instance per `parse()` call, so parse stays thread-safe): `scan(line)` finds the LAST `tvg-logo`, `group-title` and `tvg-name` values of an `#EXTINF` line with `indexOf("=\"")` scanning and keeps them as index ranges (`logoS/logoE`, `groupS/groupE`, `nameS/nameE`, -1 = not present). No Regex, no `Map`, no per-attribute substring.
  - `private fun isBlankRange(s: String, from: Int, to: Int): Boolean`.
- The old `private val ATTR = Regex(...)` is removed. Scan rules are the same as the old Regex `([A-Za-z0-9_-]+)="([^"]*)"`: key = run of letters/digits/`_`/`-` right before `="`, key compared ignoring case, value = up to the next `"`, matches never overlap, the later of two equal keys wins, the whole line is scanned (also the part after the last comma), a `="` with no closing quote ends the scan.
- Same output as before: title = text after the LAST comma of the line, trimmed; if the title is blank the `tvg-name` value (not trimmed) is used, if that is blank/missing the name is `Channel`. `logo` only when the value starts with `http` (case-sensitive). `group` only when the value is not blank (not trimmed). `#EXTVLCOPT:` handling (`http-referrer`, `http-referer`, `http-user-agent`), the reset of name/logo/group/referrer/userAgent after every non-comment line, `http://`/`https://`-only URLs, dedupe by exact URL string and the output order are unchanged. The `#EXTVLCOPT:` branch was NOT touched.
- How to verify: load the same source with the Phase 12 build and this build; the channel count on the main screen must be identical (same note is in the comment on top of `M3uParser`).
- Checked before delivery: the new attribute scan and the name/logo/group derivation were ported 1:1 to Java and compared with the old Regex logic on 3 million (scan) and 2 million (derivation) random lines with tricky characters (quotes, `=`, case changes, missing/duplicate keys, whitespace): 0 differences. The Kotlin file itself was not compiled here (no Kotlin compiler available); it uses only plain `String`/`Char` calls that exist in Kotlin 1.9.24.
- Limitations: the speed-up is in the per-line work (no Regex, no Map, fewer substrings, pre-sized collections); `BufferedReader.readLine()` still creates one `String` per line, and the playlist is still parsed on `Dispatchers.IO` by `MainViewModel.parseFile()` as before.


### Phase 14: Design tokens
- Two NEW resource files, nothing else: `R/values/dimens.xml` and `R/values/styles_text.xml`. No existing file was touched (`colors.xml`, `themes.xml`, `strings.xml`, every layout, drawable, Kotlin file, manifest and `app/build.gradle.kts` are unchanged). No new library, no new string, no new color. Nothing uses the new tokens yet (first users: Phase 15 card, later hero/row/header layouts).
- `R/values/dimens.xml` (reference as `@dimen/<name>` in XML, `R.dimen.<name>` in Kotlin):
  - `card_radius` = 12dp (corner radius of cards; `bg_card.xml` still has its own hard-coded radius until Phase 15)
  - `card_w_row` = 150dp (width of a channel card in a horizontal row)
  - `card_h_row` = 84dp (height of a channel card in a horizontal row)
  - `row_gap` = 10dp (gap between cards / rows)
  - `screen_pad` = 20dp (outer horizontal screen padding)
  - `hero_h` = 220dp (height of the hero banner)
- `R/values/styles_text.xml` (reference as `style="@style/Text.Title"` etc. on a `TextView`). All four are standalone styles declared with `parent=""` (no style named `Text` exists, so no parent is inferred from the dotted name). They set only `android:textSize`, `android:textStyle` and `android:textColor`:
  - `Text.Title`: 24sp, bold, `@color/text`
  - `Text.RowTitle`: 18sp, bold, `@color/text`
  - `Text.Meta`: 13sp, normal, `@color/text_dim`
  - `Text.Brand`: 22sp, bold, `@color/text`
- Resource names now in use: dimen `card_radius`, `card_w_row`, `card_h_row`, `row_gap`, `screen_pad`, `hero_h`; styles `Text.Title`, `Text.RowTitle`, `Text.Meta`, `Text.Brand` (next to the existing `Theme.FreeTV`, `Theme.FreeTV.Player`, `Theme.Wosux.Starting`, `Btn`).
- Notes: the styles contain no `maxLines`, `ellipsize`, font family or padding, so each layout that uses them sets those itself. Dimension values are dp/sp, so they scale with screen density (and font size for sp).
- Limitations: the build only checks that the XML is valid and the `@color/text` / `@color/text_dim` references exist (they do, since Phase 02); nothing shows these values on screen until a later phase applies them.

### Phase 15: Channel card layout (Netflix-style tile)
- Only resources changed. NO Kotlin file, `colors.xml`, `dimens.xml`, `strings.xml`, manifest or Gradle file touched. No new library, no new string, no new color. Changed: `R/layout/item_channel.xml`, `R/drawable/bg_card.xml`. Unchanged (same content): `R/drawable/bg_logo.xml` (`@color/logo_bg`, radius 8dp). New (small helper drawables, not named in the plan): `R/drawable/ratio_16_9.xml`, `R/drawable/bg_dot.xml`.
- `item_channel.xml` structure (ids in `ItemChannelBinding`; `logo`, `star`, `name` keep their ids and view types, so `ChannelAdapter` compiles and works unchanged):
  - Root `LinearLayout` (vertical, `match_parent` x `wrap_content`, margin 6dp, clickable + focusable, NO background, `android:defaultFocusHighlightEnabled="false"` so Android 8+ does not draw its own grey focus overlay on the root). `ChannelAdapter` still sets click / long-click / focus-scale on this root.
  - Tile `FrameLayout` (no id): `match_parent` x `wrap_content`, `android:background="@drawable/bg_card"`, `android:duplicateParentState="true"` (the tile shows the root's focused / pressed state, so the cyan ring is drawn on the tile).
  - 16:9 without ConstraintLayout and without Kotlin: the first child of the tile is an invisible `ImageView` (no id, `adjustViewBounds`, `fitXY`, `src=@drawable/ratio_16_9`, visibility invisible) whose height is width x 9/16; it sets the tile height. Works for any card width (grid cell `match_parent` now, fixed `cardWidthPx` in Phase 16 / rows).
  - `@id/logo` : `ImageView`, `match_parent`, margin 10dp, `background=@drawable/bg_logo` (the soft plate is the ImageView's own background, no extra layout level), padding 6dp, `fitCenter`. Hiding `logo` also hides the plate.
  - `@id/mono` : `TextView`, `match_parent`, centered, 28sp bold, `@color/text`, 1 line, visibility gone. Fills the whole tile; no background yet (Phase 16 sets the gradient and the text).
  - `@id/health` : plain `View`, 8dp x 8dp, top|start, margin 6dp, `background=@drawable/bg_dot`, visibility gone.
  - `@id/star` : `TextView` "★", same as before (18sp, hard-coded `#F59E0B`), now `top|end` of the tile, visibility gone. Becomes a heart icon in Phase 16.
  - `@id/name` : `TextView` below the tile, `match_parent`, marginTop 6dp, 12sp, `@color/text`, `maxLines=1`, `ellipsize=end`, start-aligned (was centered, 13sp, 2 lines).
- `bg_card.xml` (selector, all corners `@dimen/card_radius` = 12dp): focused = `@color/surface_focus` fill + 2dp `@color/accent` stroke (was 3dp); pressed = `@color/surface_focus` fill (no stroke, as before); default = linear gradient `#171E2A` to `#0F141C`, `android:angle="315"` (top-left to bottom-right; Android's own angle 135 would run the other way). The old default solid `@color/surface` is no longer used by this drawable.
- `ratio_16_9.xml`: shape with `<size 160dp x 90dp>` and a transparent solid; only gives the spacer its 16:9 size, never drawn. `bg_dot.xml`: oval, solid `@color/dead_gray`.
- Layout change you will see: the old root had the card background plus 8dp padding around a 76dp logo area and a 2-line name; now the tile itself is the card (no padding), 16:9, with a 1-line name under it. Cards in the grid are therefore a bit shorter. Grid span logic (now in `BrowseFragment`, moved there in Phase 17) is untouched.
- Still hard-coded: star color `#F59E0B`; gradient colors `#171E2A` / `#0F141C` in `bg_card.xml`.
- Left for Phase 16 (on purpose): `ChannelAdapter` still scales the root 1.06 and still sets `translationZ`, still never shows `mono` / `health`, and still uses the `★` text for favorites. Until then `mono` and `health` stay invisible and a channel without a logo shows the `ic_tv` icon on the light plate.
- Limitations: the 16:9 height relies on `ImageView.adjustViewBounds` (needs an exact width, which RecyclerView grid cells and fixed-width cards give); a `wrap_content` root width would collapse the tile. At very low densities (ldpi) pixel rounding can make the ratio off by about 1 px.

### Phase 16: Channel card binding (monogram, badges, focus zoom)
- Changed: `K/ChannelAdapter.kt`, `R/layout/item_channel.xml`. New: `R/drawable/ic_heart.xml`. No other Kotlin file, `colors.xml`, `dimens.xml`, `strings.xml`, manifest or Gradle file touched. No new library, no new string, no new color. `MainActivity` still compiles unchanged (it calls the adapter with the three named lambdas; the new parameter has a default).
- `ChannelAdapter` constructor: new LAST parameter `private val cardWidthPx: Int? = null`. When set, `onCreateViewHolder` sets `root.layoutParams.width = cardWidthPx` (height stays wrap_content, so the 16:9 tile follows the width). When null the card keeps the width the layout manager gives it (grid cell, as before).
- `VH` gained `var boundUrl: String?` (url of the channel the holder shows now; only used to ignore a late logo-error callback of a recycled holder). `VH(val b: ItemChannelBinding)` is otherwise unchanged. `CHANNEL_DIFF`, click and long-click handling are unchanged.
- Binding in `onBindViewHolder` (cheap work only):
  - name text; `star` visible when `isFavorite(c)`.
  - Health: `HealthStore.state(c.url) == HealthStore.State.DEAD` gives `root.alpha = 0.45f` and `health` visible; otherwise alpha 1f and `health` gone. (The dot sits inside the root, so it is dimmed together with the card; its colour is `@color/dead_gray` through `bg_dot.xml`.)
  - Logo present: `mono` gone, `logo` visible, `logo.load(url) { placeholder(R.drawable.ic_tv); listener(onError = ...) }`. On a load error (and only if the holder is still bound to the same channel) `logo` is hidden and the monogram is shown. The `error(...)` drawable is no longer set.
  - Logo null/blank: `logo.setImageDrawable(null)`, `logo` gone, monogram shown.
  - Monogram (`private fun showMono`, `private fun monogram`): text = first 1-2 letters/digits of the name, upper case (`"TV"` if the name has none); background = a new `GradientDrawable` (orientation TL_BR, corner radius = `card_radius` minus 2dp) from `MONO_GRADIENTS[(name.hashCode() and Int.MAX_VALUE) % 6]`. The 6 gradients are `private val` hex pairs in the Kotlin file (blue, violet-blue, teal, magenta-orange, green, red-rose), all dark enough for white text.
  - A recycled card gets the scale of its focus state without animation (1.08 if `root.isFocused`, else 1), so a rebind of the focused card does not shrink it.
- Focus: `setOnFocusChangeListener` animates `scaleX` / `scaleY` to 1.08 (focused) or 1 in 120 ms. `translationZ` is gone. Constants (private, top level in the file): `FOCUS_SCALE = 1.08f`, `DEAD_ALPHA = 0.45f`.
- `R/layout/item_channel.xml`: `@id/mono` got `layout_margin` 2dp (so the 2dp accent focus ring of the tile's `bg_card` is not covered by the gradient); `@id/star` is now an `ImageView` 18dp x 18dp (top|end, margin 6dp, `src=@drawable/ic_heart`, decorative, gone by default; it was a `TextView` with "★" and `#F59E0B`). Everything else is unchanged. In `ItemChannelBinding` the type of `star` changed from `TextView` to `ImageView` (nothing else in the project uses it besides `ChannelAdapter`'s `isVisible`).
- `R/drawable/ic_heart.xml`: vector 18dp x 18dp, viewport 24 x 24, Material "favorite" heart, `fillColor=@color/accent`.
- Resource names now in use: drawable `ic_heart`. Ids used by the adapter: `logo`, `mono`, `health`, `star`, `name`.
- Not changed on purpose / limitations:
  - The dead badge is read when a card is bound. `HealthStore.version` is not observed yet and DiffUtil compares only `Channel`, so a card changes from normal to dead (or back) the next time it is bound (scroll, refresh, `notifyItemChanged`), not at the moment the state changes. Hooking `HealthStore.version` to the adapter is a later phase.
  - Without `translationZ` the zoomed card is drawn in child order, so its enlarged edge can sit under the next card; the 6dp card margin and the 8% scale keep the overlap inside the gap (list has `clipChildren=false`).
  - The compile of the Kotlin file was checked by reading only (no Kotlin compiler here). It uses `ImageRequest.Builder.listener(onError = ...)` from Coil 2.7.0.

### Phase 17: Move the old screen into BrowseFragment (no visual change)
- Changed: `app/build.gradle.kts` (new dependency `implementation("androidx.fragment:fragment-ktx:1.8.2")`, placed after activity-ktx; no other version changed), `K/MainActivity.kt` (now a thin host), `R/layout/activity_main.xml` (now only a FragmentContainerView).
  New: `K/BrowseFragment.kt`, `R/layout/fragment_browse.xml`. No other Kotlin, resource, manifest or workflow file touched. No new string, color or drawable.
- `R/layout/fragment_browse.xml` = the previous `activity_main.xml` copied unchanged (same root LinearLayout with `@color/bg` background and padding, same ids). `FragmentBrowseBinding` is generated from it (`ActivityMainBinding` now only has `container` / `root`).
- `R/layout/activity_main.xml`: one `androidx.fragment.app.FragmentContainerView` `@+id/container`, match_parent x match_parent, `android:background="@color/bg"`. It has no `android:name`; the fragment is added in code.
- `K/BrowseFragment.kt`: all former MainActivity logic, same behavior (see its section above). Differences that are only because it is a fragment: view binding is created in `onCreateView` / `onViewCreated` and nulled in `onDestroyView`;
  observers of `vm.channels`, `vm.loading`, `vm.source`, `vm.message` use `viewLifecycleOwner`; `requireContext()` replaces `this` for `GridLayoutManager`, `Toast`, `Intent`, `AlertDialog.Builder`, `EditText`, `FrameLayout`;
  the delayed focus request in `onResume` captures `b.list` first so it never reads the binding after the view is gone.
- `K/MainActivity.kt`: keeps `installSplashScreen()` before `super.onCreate`, `ActivityMainBinding.inflate` + `setContentView`, and `CrashLog.showIfAny(this)`. Adds `BrowseFragment()` into `R.id.container` with `supportFragmentManager.commit { add(...) }` only when `savedInstanceState == null`
  (after rotation / process restore the FragmentManager brings the fragment back). It no longer has `vm`, adapter, dialogs or `onResume`.
- Public signatures: `BrowseFragment()` (no arguments yet). `MainViewModel` is shared through the activity, so later fragments can use `by activityViewModels<MainViewModel>()` and will see the same data, query, `favoritesOnly` and source.
- Not changed on purpose / limitations:
  - Checked by reading only, no Kotlin compiler was available here. Imports used: `androidx.fragment.app.Fragment`, `androidx.fragment.app.activityViewModels`, `androidx.fragment.app.commit` (all from fragment-ktx 1.8.2).
  - `vm.query` and `vm.favoritesOnly` still live in the ViewModel, so the search text and favorites filter survive rotation exactly as before. `visible` and `cameFromPlayer` are fragment fields (previously activity fields), so they reset on rotation as before.

### Phase 18: Row models + RowBuilder
- New: `K/HomeModels.kt`, `K/RowBuilder.kt`. Nothing else changed (no layout, resource, string, Gradle, manifest or existing Kotlin file). Not used by any screen yet (Phase 19/20 use it).
- `HomeModels.kt`: `Row` and `HomeItem` exactly as in the Public signatures above (`HomeItem.Hero(channel)`, `HomeItem.RowItem(row)`).
- `RowBuilder.build(all, favorites, recents, hideDead)` returns rows in this order, never an empty row:
  1. `Row("recents", "Continue watching", ...)`: first 20 of `recents` (list order kept, so pass them newest first).
  2. `Row("favs", "My List", ...)`: first 30 of `favorites`.
  3. Category rows `Row("group:<name>", "<name>", ...)`: a channel's `group` is split on `;`, each part trimmed, blank parts and "Undefined" (any letter case) skipped. A channel counts once per category
     even if the same category is listed twice. Categories are sorted by channel count, biggest first (equal counts keep the order they first appear in `all`), top 14 kept.
     `items` = up to 30 channels, those with a non-blank logo first, then the rest, each part in original order. `total` = number of channels in that category (not capped at 30).
  4. Only when NO category row exists (no channel has a usable category, or `all` has no groups at all): `Row("all", "Channels", first 60 of all, total = all.size)`. If `all` is empty, no such row.
- `hideDead = true`: channels with `HealthStore.state(url) == DEAD` are removed everywhere (recents, favs, category lists, counts, the "all" row). With `hideDead = false` HealthStore is not called at all.
- Titles "Continue watching", "My List", "Channels" are plain string literals in `RowBuilder` (pure Kotlin has no Context). Category titles are the category names as written in the playlist (case-sensitive: "News" and "news" would be two rows).
- `total` for "recents" and "favs" equals `items.size`, so `total > items.size` is false for them (a HomeAdapter that shows "See all" only when `total > items.size` will not show it there). For "all", `total` is the full count, so it can show "See all"; Phase 24 only defines "See all" for ids starting with `group:`, so ignore or handle "all" there.
- `RowBuilder.pickHero(all)`: first channel in list order whose `logo` is non-blank and whose state is not DEAD; null if none.
- Thread safety: no state, no UI, no Context; only `HealthStore.state` (synchronized inside) is read. Run it on `Dispatchers.Default`; for a very large playlist it makes one pass over `all` (plus one `filter` pass when `hideDead`).
- Checked by reading only (no Kotlin compiler here); the `;` splitting loop was also checked on edge cases (`"A;;B;"`, `" Undefined "`, `";"`, `""`, `"A; B ;A"`) in a line-by-line port.

### Phase 19: HomeAdapter (vertical list of horizontal rows)
- New: `K/HomeAdapter.kt`, `R/layout/item_row.xml`. Changed: `K/ChannelAdapter.kt` (3 small edits, reason below), `API_MAP.md`. No other Kotlin, resource, string, color, dimen, manifest or Gradle file touched. No new library. Not shown on any screen yet (Phase 20 creates `HomeFragment`).
- `HomeAdapter(isFavorite, onPlay, onLong, onSeeAll) : ListAdapter<HomeItem, RecyclerView.ViewHolder>` (signature in the section above). Imports used: `android.os.Parcelable`, `android.view.{LayoutInflater, View, ViewGroup}`, `androidx.core.view.isVisible`, `androidx.recyclerview.widget.{DiffUtil, LinearLayoutManager, ListAdapter, RecyclerView, SimpleItemAnimator}`, `com.freetv.app.databinding.ItemRowBinding`.
- DiffUtil: `areItemsTheSame` = both `RowItem` with the same `row.id` (or both `Hero`); `areContentsTheSame` = `==` (data classes, so a changed channel list / title / total makes that row re-bind).
- View types: `VT_ROW = 0` (private) draws a row; `VT_HERO = 1` (private) gets `EmptyVH`, a 0 dp high `View`, so a `HomeItem.Hero` in the list does nothing visible and cannot crash. Phase 22 replaces it.
- Row holder `RowVH`: created in `onCreateViewHolder` with its own `LinearLayoutManager(HORIZONTAL)` (`initialPrefetchItemCount = 5`) and its own inner `ChannelAdapter(isFavorite, onClick, onLongClick, cardWidthPx = R.dimen.card_w_row in px)`. Inner list: `setHasFixedSize(true)`, `itemAnimator = null`, `setRecycledViewPool(pool)`.
  - ONE `RecycledViewPool` per `HomeAdapter` (private field), `setMaxRecycledViews(0, 24)` (view type 0 = channel card; the default would keep only 5). All rows use it.
  - Card click = `onPlay(row, index)`, `index` = position in `row.items` (the inner list IS `row.items`). Card long-click = `onLong(channel)`. `row` is read from `RowVH.row` at click time, so a re-used holder always answers for the row it shows now.
  - `rowTitle` = `row.title`. `rowMore` ("See all >", hard-coded text with `tools:ignore="HardcodedText"`, no new string) is visible only when `row.total > row.items.size`; click = `onSeeAll(row)`. So `recents` / `favs` (total == items.size) never show it; the `all` row and `group:*` rows can.
  - `onAttachedToRecyclerView`: sets `supportsChangeAnimations = false` on the OUTER list's `SimpleItemAnimator` (if it has one), so a changed row is rebound in place instead of cross-fading into a second holder.
- Scroll memory: `private val scrollStates = HashMap<String, Parcelable>()` (rowId -> `LinearLayoutManager.onSaveInstanceState()`).
  - Saved when a row holder is recycled (`onViewRecycled`) and when a holder is re-bound to a DIFFERENT row id; only if the inner list has laid-out children.
  - Restored after the new row's cards are in the inner adapter (`submitList(items) { restoreState }`, so an asynchronous diff cannot make it run too early); the entry is removed when used. A row with no saved state is scrolled to the start (this matters because a holder from the pool still has the previous row's position). The callback does nothing if the holder was re-bound or recycled meanwhile (`row.id` check).
  - A re-bind of the SAME row id (e.g. the same row with new channels) does not touch the scroll position.
  - Rows kept in the RecyclerView's view cache are not re-bound at all and simply keep their position. The map lives as long as the adapter (not saved across rotation or process death).
  - `setRecycleChildrenOnDetach` is NOT used on purpose: it would drop the cards of a cached row when it is detached and the row would jump back to the start when re-attached.
- `K/ChannelAdapter.kt` edits (needed because rows share one pool; public signature and behaviour for the old grid unchanged):
  1. Click and long-click now use `val a = (vh.bindingAdapter as? ChannelAdapter) ?: this` and call `a.onClick(a.getItem(p), p)` / `a.onLongClick(...)`. Before, they used the adapter that CREATED the holder; a card created by row A and re-used in row B would have played the wrong channel / called the wrong row. (`bindingAdapter` exists since RecyclerView 1.2.0; the project has 1.3.2.)
  2. The `private var monoRadiusPx` field and its two assignment lines in `onCreateViewHolder` are gone; `showMono` computes the same radius (`card_radius` minus 2dp) from `holder.itemView.resources`. Before, an adapter that only re-used pooled holders would have kept 0 and drawn square monograms over the rounded focus ring.
- Resource names now in use: layout `item_row`; ids `rowTitle`, `rowMore`, `rowList`. Uses existing `@style/Text.RowTitle`, `@dimen/screen_pad`, `@dimen/card_w_row`, `@drawable/bg_button`, `@color/text`.
- `item_row.xml`: root vertical `LinearLayout` (match_parent x wrap_content) = header `LinearLayout` (horizontal, min height 44dp, top padding 12dp, side padding `screen_pad`; `rowTitle` weight 1, 1 line, ellipsized, 6dp start margin so it lines up with the card edge; `rowMore` right, `bg_button` pill with the accent ring when focused, clickable + focusable, gone by default) + `RecyclerView @id/rowList` (match_parent x wrap_content, `clipToPadding=false`, start / end padding `screen_pad`). Layout manager and pool are set in code. 3 views + 1 container, no deeper nesting.
- Notes for Phase 20 and later:
  - A heart is drawn when a card is bound. Toggling a favorite and rebuilding the rows does NOT redraw the heart of a card whose row did not change (the diff sees equal rows). The `favs` row changes, others do not. (Solved in Phase 20 with `HomeAdapter.refreshCards()`, see below.)
  - Same for the dead badge: it follows the card's bind time only (as noted in Phase 16).
  - `HomeAdapter` does no focus handling of its own: vertical D-pad movement between rows is the normal Android focus search.
- Limitations: checked by reading only (no Kotlin compiler or Android SDK was available here). `setHasFixedSize(true)` with a `wrap_content` height relies on all cards having the same height (true: 16:9 tile + 1-line name).

### Phase 20: HomeFragment (the new start screen)
- New: `K/HomeFragment.kt`, `R/layout/fragment_home.xml`. Changed: `K/MainActivity.kt`, `K/HomeAdapter.kt` (small addition, reason below), `API_MAP.md`. No other Kotlin, resource, string, color, dimen, manifest or Gradle file touched. No new library, no new string (reuses `src_all` = "All channels", `empty_list`, `fav_added`, `fav_removed`).
- `R/layout/fragment_home.xml`: root vertical `LinearLayout` (`@color/bg`, top padding 12dp) = header `LinearLayout` (horizontal, center-vertical, side padding `screen_pad`) + `FrameLayout` (weight 1, `clipChildren=false`) holding `RecyclerView @id/homeList` (match_parent, `clipToPadding=false`, `clipChildren=false`, bottom padding 24dp) and `TextView @id/empty` (centered, `text_dim`, 16sp, `@string/empty_list`, gone).
  - Header: wordmark = nested horizontal `LinearLayout` (start margin 6dp so it lines up with the row titles) with `TextView` "Wosux" (`style Text.Brand` = 22sp bold white) and `TextView` "STREAM" (`@color/brand_cyan`, 13sp bold, `letterSpacing 0.3`, 8dp start margin; baseline aligned with "Wosux"). Both texts are hard-coded (brand name, `tools:ignore="HardcodedText"`). Then `ProgressBar @id/progress` (small, 22dp, gone), a weight-1 spacer, and `Button @id/btnBrowse` (`style Btn`, `@string/src_all`, min height 36dp, 13sp, 14dp side padding; `Btn` is focusable and `bg_button` draws the 3dp accent ring when focused).
- `HomeFragment`:
  - `onViewCreated`: `HomeAdapter(isFavorite = { Prefs.isFavorite(it.url) }, onPlay = play, onLong = toggleFavorite, onSeeAll = { })` (`onSeeAll` is an empty lambda until Phase 24), `homeList` = `LinearLayoutManager` (vertical) + `setHasFixedSize(true)` (change animations are switched off by `HomeAdapter`). `btnBrowse` click = `(activity as? MainActivity)?.openBrowse()` (since Phase 21 this switches to the Browse tab).
  - Observers use `viewLifecycleOwner`: `vm.channels` -> `rebuild()`; `vm.loading` -> `progress.isVisible = loading` + `updateEmpty()`; `vm.message` -> `Toast` (LENGTH_LONG) then `vm.message.value = null` (same as BrowseFragment: Home is the first screen now, so the load / refresh error messages must show here; BrowseFragment does the same when it is on top, only one of them is alive at a time).
  - `rebuild()`: takes `vm.channels.value` on the main thread, cancels the previous `buildJob`, then in `viewLifecycleOwner.lifecycleScope.launch` runs `RowBuilder.build(all, loadFavorites(), emptyList(), false)` in `withContext(Dispatchers.Default)` (recents = empty until Phase 25, hideDead = false until Phase 32). Back on the main thread: `rowCount = rows.size`, `adapter.submitList(rows.map { HomeItem.RowItem(it) })`, `updateEmpty()`. A cancelled build never submits (the `withContext` throws `CancellationException` on resume); the job is also cancelled in `onDestroyView`.
  - `loadFavorites()` = `Prefs.favorites()` called inside the Default block, so the first-use JSON parse of the favorites is off the main thread. `Prefs` is not thread-safe: if a toggle on the main thread lands exactly while the background copy runs, a `ConcurrentModificationException` is caught and this pass has no "My List" row; every toggle starts a new `rebuild()` right after, so the final state is always built. (Making `Prefs` thread-safe is Phase 39's job.)
  - `updateEmpty()`: `empty.isVisible = rowCount == 0 && vm.loading.value != true`.
  - Click a card: `play(row, index)` = ignore if `index !in row.items.indices`; `ChannelStore.playlist = row.items`, `ChannelStore.index = index`, `cameFromPlayer = true`, `startActivity(Intent(requireContext(), PlayerActivity::class.java))`. So the player zaps inside that row.
  - Long-press a card: `Prefs.toggleFavorite(ch)`, `Toast` (`fav_added` / `fav_removed`, as in BrowseFragment), `adapter.refreshCards()` (hearts of that channel in all visible rows), `rebuild()` ("My List" row appears / changes / disappears).
  - `onResume`: only when `cameFromPlayer` (set when a card started the player): reset it, `adapter.refreshCards()`, `rebuild()` (favorites may have changed in the player).
  - Imports used: `android.content.Intent`, `android.os.Bundle`, `android.view.{LayoutInflater, View, ViewGroup}`, `android.widget.Toast`, `androidx.core.view.isVisible`, `androidx.fragment.app.{Fragment, activityViewModels}`, `androidx.lifecycle.lifecycleScope` (lifecycle-runtime-ktx 2.8.4, already a dependency), `androidx.recyclerview.widget.LinearLayoutManager`, `com.freetv.app.databinding.FragmentHomeBinding`, `kotlinx.coroutines.{Dispatchers, Job, launch, withContext}`, `java.util.ConcurrentModificationException`.
- `K/MainActivity.kt`: the first fragment added when `savedInstanceState == null` is now `HomeFragment()` (was `BrowseFragment()`); new `fun openBrowse()` = `replace(R.id.container, BrowseFragment())` + `addToBackStack(null)` inside `supportFragmentManager.commit`. `installSplashScreen()`, `CrashLog.showIfAny(this)` and the binding lines are unchanged. BACK on Browse returns to Home; BACK on Home leaves the app.
- `K/HomeAdapter.kt` (addition not named in the task, needed so favorites look right): new public `fun refreshCards()`. Without it, toggling a favorite (here or in the player) would only change the "My List" row and leave the hearts of the same channel in the category rows wrong, because DiffUtil sees those rows as unchanged.
  - Mechanism: private `cardsEpoch` counter; `refreshCards()` does `cardsEpoch++` and `notifyItemRangeChanged(0, itemCount, PAYLOAD_CARDS)`. The new `onBindViewHolder(holder, position, payloads)` handles that payload by calling `inner.notifyItemRangeChanged(0, inner.itemCount)` for the row only (title, row list and scroll position are untouched). `RowVH.epoch` records which epoch a holder's cards were bound under; `bindRow` also re-binds the cards of a holder whose epoch is old (a row that sat in the pool during `refreshCards()`), after the row's list is committed. A new holder starts with the current epoch. Everything else in `HomeAdapter` is unchanged.
- Behaviour you will see:
  - The app opens on Home. While nothing is loaded yet it shows the spinner (and, once loading has finished with no rows, the `empty_list` text). With a cached playlist the rows appear as soon as `vm.channels` has it.
  - Row order is RowBuilder's: My List (if any favorites), then up to 14 categories (a row of 30 channels, logos first). No "Continue watching" yet. "See all >" shows on category rows with more than 30 channels but does nothing yet (Phase 24).
  - "All channels" opens the old grid unchanged (Source / Search / Favorites / Refresh). The filter state (`vm.query`, `vm.favoritesOnly`) lives in the ViewModel as before.
- Limitations:
  - Checked by reading only (no Kotlin compiler or Android SDK was available here).
  - After BACK from Browse, Home's view is created again: the vertical position is restored by the system, but the horizontal positions of the rows start at the left (the `HomeAdapter` is created with the view). Keeping them is Phase 24's "same scroll position" job.
  - On a TV the first focus goes to `btnBrowse` (first focusable view); press DOWN to enter the rows. Hero / default focus is Phase 22.
  - Dead badges and `HealthStore` changes are not observed yet (Phase 29).

### Phase 21: Navigation bar (phone: bottom, TV: top)
- New: `K/ComingSoonFragment.kt`, `R/layout/view_nav.xml`, `R/layout/fragment_coming_soon.xml`, `R/drawable/bg_nav_item.xml`, `R/drawable/ic_nav_home.xml`, `R/drawable/ic_nav_browse.xml`, `R/drawable/ic_nav_list.xml`, `R/drawable/ic_nav_settings.xml`.
  Changed: `K/MainActivity.kt`, `R/layout/activity_main.xml`, `R/values/strings.xml` (5 new strings appended), `API_MAP.md`. No other Kotlin, resource, color, dimen, manifest or Gradle file touched. No new library. No Material class used.
- `R/layout/view_nav.xml`: root horizontal `LinearLayout` (match_parent x wrap_content, `@color/surface`, padding 8dp sides / 4dp top+bottom, `baselineAligned=false`) with 4 equal items (width 0dp, weight 1, height 56dp, margin 4dp sides). Each item is a vertical `LinearLayout`
  (clickable + focusable, `bg_nav_item`, centered, `defaultFocusHighlightEnabled=false`) holding an `ImageView` (24dp, decorative, `@drawable/ic_nav_*`) and a `TextView` (12sp bold, 1 line, ellipsized, `@color/text_dim` by default).
  Item ids: `navHome`, `navBrowse`, `navList`, `navSettings`; icon ids `<item>Icon`; label ids `<item>Label`. Labels: `@string/nav_home` "Home", `nav_browse` "Browse", `nav_list` "My List", `nav_settings` "Settings". The root has no id (the include gives it `nav`).
- `bg_nav_item.xml` (selector, radius 12dp): focused = `@color/surface_focus` + 2dp `@color/accent` ring; pressed = `@color/surface_focus`; default = transparent. The 4 icons are 24dp vectors with a white fill; the tint is applied in code (`setColorFilter`).
  Paths: home = Material "home", browse = Material "apps" (3x3 grid), list = Material "bookmark", settings = Material "settings" (gear).
- `R/layout/activity_main.xml`: vertical `LinearLayout` (`@color/bg`) = `FragmentContainerView @id/container` (height 0dp, weight 1, no `android:name`) + `<include android:id="@+id/nav" layout="@layout/view_nav">` at the bottom. So the phone layout needs no code; `ActivityMainBinding.nav` is a `ViewNavBinding` and `b.nav.root` is the nav LinearLayout.
- `MainActivity`:
  - TV detection: `packageManager.hasSystemFeature(FEATURE_LEANBACK) || !packageManager.hasSystemFeature(FEATURE_TOUCHSCREEN)`. If TV: `b.root.removeView(nav)` then `b.root.addView(nav, 0)` (the nav keeps its layout params) = nav on top.
  - Tabs (index -> fragment, tag): 0 Home -> `HomeFragment` `tab_home`; 1 Browse -> `BrowseFragment` `tab_browse`; 2 My List -> `ComingSoonFragment` `tab_list`; 3 Settings -> `ComingSoonFragment` `tab_settings`.
  - `selectTab(tab)`: in one `supportFragmentManager.commitNow { }` it hides every other tab fragment that exists and either adds the target (first visit only, with its tag) or shows it. Fragments are never replaced or removed, so their state (scroll position, filters, dialogs) is kept and a switch is instant.
    A tab fragment is created on its first visit (Browse, My List, Settings are not built at app start; Home is added at start). Does nothing when the tab is already the visible one.
  - Colors: selected item = `@color/accent` icon tint + label; the others `@color/text_dim` (set in `updateNav()`, also sets `isSelected` on the item). Clicking an item = `selectTab(i)`. On a TV the nav item keeps focus after a click; DOWN (nav on top) moves into the content and UP from the first row moves back to the nav, by the normal focus search.
  - BACK: an `OnBackPressedCallback` is enabled only while the current tab is not Home; it selects Home and puts focus on the Home nav item. On Home it is disabled, so BACK leaves the app as before.
  - Rotation / recreation: `onSaveInstanceState` saves the tab index; `onCreate` calls `selectTab(saved or Home)` after the FragmentManager restored the fragments, which only re-applies show / hide.
  - `openBrowse()` now = `selectTab(TAB_BROWSE)` + `requestFocus()` on the Browse nav item. The old `replace(...)` + `addToBackStack(null)` flow is gone. `HomeFragment` and its "All channels" button (`btnBrowse`) are unchanged and still call it.
- `ComingSoonFragment`: one centered `TextView` `@string/coming_soon` ("Coming soon", `@color/text_dim`, 18sp) on `@color/bg`. Nothing focusable on it, so on a TV the focus stays on the nav.
- Resource names now in use: layouts `view_nav`, `fragment_coming_soon`; drawables `bg_nav_item`, `ic_nav_home`, `ic_nav_browse`, `ic_nav_list`, `ic_nav_settings`; strings `nav_home`, `nav_browse`, `nav_list`, `nav_settings`, `coming_soon`; ids listed above.
- Behaviour you will see:
  - Phone: bar at the bottom, 4 tabs, Home selected at start. TV box / device without touchscreen: the same bar at the top.
  - "All channels" on Home switches to the Browse tab (the Browse tab is built then; its search / favorites filter state lives in `MainViewModel` as before). BACK from Browse, My List or Settings goes to Home.
  - Home and Browse both stay alive once opened, so both observe the shared `MainViewModel`; the load / refresh message Toast is shown once (whichever observer sees it first clears it).
- Limitations:
  - Checked by reading only (no Kotlin compiler or Android SDK was available here).
  - On a TV the first focus at app start is the Home item of the nav (it is now the first focusable view); press DOWN to enter the rows. Hero / default focus is still Phase 22.
  - Hidden tab fragments stay RESUMED (show / hide does not change the lifecycle), so they keep observing LiveData while hidden. This is what keeps their state and makes switching instant.
  - The nav is not hidden inside the player (the player is its own activity).

### Phase 22: Hero banner
- New: `R/layout/item_hero.xml`, `R/drawable/bg_hero.xml`, `R/drawable/bg_button_primary.xml`, `R/drawable/bg_live.xml`, `R/values/dimens_hero.xml`, `R/values-w600dp/dimens_hero.xml`.
  Changed: `K/HomeAdapter.kt`, `K/HomeFragment.kt`, `R/values/strings.xml` (4 strings appended), `API_MAP.md`. No other Kotlin, resource, manifest or Gradle file touched. No new library. `RowBuilder`, `HomeModels`, `ChannelAdapter`, `MainActivity` are unchanged.
- `item_hero.xml`: root horizontal `LinearLayout`, height `@dimen/hero_h` (220dp), `layout_margin` `@dimen/screen_pad`, `background=@drawable/bg_hero`, padding 16dp, children centered vertically.
  - Left (vertical `LinearLayout`, weight 1): red chip `TextView` "LIVE" (`@string/hero_live`, `bg_live`, 11sp bold white, letter spacing 0.1); `@id/heroName` (28sp bold `@color/text`, max 2 lines, ellipsized, 8dp top margin);
    `@id/heroCategory` (`style Text.Meta`, 1 line, gone until a category exists); button row (14dp top margin): `@id/btnWatch` (`style Btn`, `bg_button_primary`, text `@color/bg`, 14sp bold, min height 36dp, `@string/hero_watch`) and
    `@id/btnHeroList` (`style Btn`, 14sp, min height 36dp, 8dp start margin, `@string/hero_list` or `@string/hero_in_list`). Both buttons are focusable (`Btn` sets it).
  - Right: `FrameLayout` (`@dimen/hero_plate_w` x `@dimen/hero_plate_h`, 12dp start margin) with `@id/heroLogo` (ImageView, `bg_logo` plate as its own background, 10dp padding, fitCenter) and `@id/heroMono` (TextView, 40sp bold white, centered, gone; gets a gradient background in code).
  - 4 levels at most, 1 background on the root. The plate is 88dp square on phones and 240dp x 135dp (16:9) from 600dp width (TV, tablet, landscape).
- `bg_hero.xml`: `layer-list`: (1) rectangle, linear gradient `#0B2A4A` to `#0A0D12`, angle 0 (left to right), corners `@dimen/card_radius`; (2) rectangle, radial gradient (center x 0.12, y 0.5, radius 260dp) `#3302E1FE` to `#0002E1FE` = the soft cyan glow on the left, same corners.
- `bg_button_primary.xml` (selector, radius 22dp): default = gradient `@color/brand_blue` to `@color/brand_cyan` (left to right); pressed = `brand_blue` to `brand_blue_mid`; focused = the default gradient + a 3dp `@color/text` (white) ring (an accent ring would disappear on the cyan end). Dark text comes from `textColor=@color/bg` in the layout.
- `bg_live.xml`: rectangle, `@color/live_red`, radius 6dp.
- `HomeAdapter` (new view type `VT_HERO = 1`, the zero-height `EmptyVH` is gone):
  - Constructor: two new LAST parameters with defaults, `onHeroWatch: (Channel) -> Unit = { }` and `onHeroList: (Channel) -> Unit = { }`. The four old parameters are unchanged.
  - `class HeroVH(val b: ItemHeroBinding)` (public, so `HomeFragment` can find it): `var channel: Channel?` (shown now), `var boundUrl: String?`. Click listeners are set once in `createHero` and read `vh.channel` at click time.
  - `bindHero(h, c)` (cheap work only): name, category text (up to 3 categories of `c.group`, split on `;`, trimmed, "Undefined" skipped, joined with `  ·  `; the text view is hidden when empty), and the "My List" button text (`hero_in_list` "✓ My List" when `isFavorite(c)`, else `hero_list`).
    Logo: when the channel changed, `heroLogo.load(url) { placeholder(ic_tv); listener(onError = ...) }` (Coil, same loader as the cards); on a load error, or when the channel has no logo, the logo is hidden and the initials are shown (`showHeroMono`: first 1-2 letters/digits upper case, `"TV"` if none, on one of 6 gradients chosen by `name.hashCode()`, radius 8dp like the plate).
    When only the favorite changed (same url), the logo / initials are left alone, so nothing flickers.
  - DiffUtil is unchanged (`Hero` = same item as any other `Hero`; contents compared with `==`). `refreshCards()` also re-binds the hero (through the 2-argument `onBindViewHolder`), which is how the button follows a favorite change.
- `HomeFragment`:
  - `rebuild()`: the Default-dispatcher block now returns `Built(RowBuilder.build(...), RowBuilder.pickHero(all))`. On the main thread: `currentRows = rows`, then the list is `[HomeItem.Hero(hero)] + rows.map(RowItem)` (no Hero item when `pickHero` returned null). `pickHero` is stable (first channel with a logo that is not DEAD), so a rebuild keeps the same hero and DiffUtil does not re-bind it.
  - "Watch" = `playHero(ch)`: `ChannelStore.playlist` = the hero channel first, then the items of the FIRST row (`currentRows.first().items`, without the hero channel's url again), `ChannelStore.index = 0`, `cameFromPlayer = true`, start `PlayerActivity`. If there is no row, the playlist is just the hero channel.
  - "My List" button = the existing `toggleFavorite(ch)` (toast, `adapter.refreshCards()`, `rebuild()`), the same as a long-press on a card.
  - TV focus: `pendingHeroFocus = isTv()` is set in `onViewCreated` (`isTv()` = leanback feature or no touchscreen, the same test as `MainActivity`). After each `submitList` (commit callback) `focusHeroIfNeeded()` waits for the next layout of `homeList` (`doOnNextLayout`),
    then, if the focus is not already inside the list and the first item is the hero, calls `btnWatch.requestFocus()` and clears the flag only when that succeeded (so it is retried if the channels arrive late, and it does nothing while Home is hidden). The callback checks `_b` for null (the view may be gone before the diff finishes).
  - New imports: `android.content.pm.PackageManager`, `androidx.core.view.doOnNextLayout` (core-ktx 1.13.1).
- Resource names now in use: layout `item_hero`; ids `heroName`, `heroCategory`, `btnWatch`, `btnHeroList`, `heroLogo`, `heroMono`; drawables `bg_hero`, `bg_button_primary`, `bg_live`; dimens `hero_plate_w`, `hero_plate_h`; strings `hero_live`, `hero_watch`, `hero_list`, `hero_in_list`.
- Behaviour you will see: Home starts with the banner (dark blue with a cyan glow on the left, red LIVE chip, name, category, Watch / My List, logo plate on the right), then the rows. Watch plays the hero channel and UP / DOWN zaps through the first row. On TV the Watch button has the focus when Home first appears.
- Limitations:
  - Checked by reading only (no Kotlin compiler or Android SDK was available here).
  - The hero is simply the first channel with a logo (Phase 22 task); it does not rotate. `HealthStore` changes are not observed yet (Phase 29), so a hero that dies stays until the next rebuild.
  - On a very narrow phone (about 320dp) the two buttons can be squeezed a little next to the 88dp plate.
  - On TV the focus is taken when the first list with a hero is laid out; if the user moves the focus into a row before that, it is left alone. A focused nav item (nav on top) does not stop it.

### Phase 23: Skeleton shimmer loading
- New: `K/ShimmerView.kt`, `R/layout/view_home_skeleton.xml`. Changed: `R/layout/fragment_home.xml`, `K/HomeFragment.kt`, `API_MAP.md`. No other Kotlin, resource, string, color, dimen, drawable, manifest or Gradle file touched. No new library, no new string, no new color.
- `ShimmerView` (custom `View`, 3-argument constructor with `@JvmOverloads`, so the XML inflater works):
  - Draws one `drawRoundRect` (radius `R.dimen.card_radius`, 12dp) with a `LinearGradient` as wide as the view: colors `R.color.surface`, `R.color.surface_focus`, `R.color.surface` at 0 / 0.5 / 1, `TileMode.CLAMP`. The gradient is created in `onSizeChanged` (once per size change, never in `onDraw`; `onDraw` allocates nothing).
  - Sweep: a `ValueAnimator.ofFloat(0f, 1f)`, 1300 ms, `LinearInterpolator`, `INFINITE`. Each frame moves the gradient with `shader.setLocalMatrix` from one view width left of the view to one view width right of it (outside the gradient the clamped color is `surface`, so only the highlight crosses the view), then `invalidate()`. No allocation per frame.
  - The animator runs ONLY while `isAttachedToWindow && isShown && windowVisibility == VISIBLE`. It is re-checked in `onAttachedToWindow`, `onVisibilityChanged` (also fires when a PARENT changes visibility, which is how hiding the skeleton root stops all 16), and `onWindowVisibilityChanged`. `onDetachedFromWindow` always stops it (update listeners removed, then `cancel()`). A hidden fragment (Home on another tab) therefore costs nothing.
  - `importantForAccessibility = NO`, not focusable, not clickable. If the system animation scale is 0 the blocks are shown without movement.
- `R/layout/view_home_skeleton.xml`: root vertical `LinearLayout` (no id, no `<merge>`, so the include can carry an id and be hidden as one view) holding plain `ShimmerView`s, no RecyclerView, 2 levels:
  - 1 hero block: `match_parent` x `@dimen/hero_h`, margin `@dimen/screen_pad` (same box as `item_hero.xml`).
  - 3 rows (horizontal `LinearLayout`, side padding `@dimen/screen_pad`, top margin 36dp for the first row and 44dp for the others = about the height of a row title) of 5 blocks each: `@dimen/card_w_row` x `@dimen/card_h_row`, margin 6dp (same as the cards). On a phone the 4th and 5th block are cut off by the screen edge.
- `fragment_home.xml`: inside the weight-1 `FrameLayout`, after `homeList` and before `empty`: `<include android:id="@+id/skeleton" layout="@layout/view_home_skeleton" match_parent x match_parent visibility="gone">`. HomeFragment controls the visibility (`gone` is only the state before the first update).
- `HomeFragment`:
  - New `private fun updateSkeleton()`: `b.skeleton.root.isVisible = rowCount == 0 && (vm.loading.value == true || !vm.channels.value.isNullOrEmpty())`. So it is shown while no rows have been submitted yet AND (a load is running OR channels already arrived but the rows are still being built on Dispatchers.Default). It is hidden as soon as `rebuild()` has submitted rows (`rowCount > 0`), and when loading ends with no data (then the `empty` text shows).
  - `updateEmpty()` now starts with `updateSkeleton()` and shows the `empty` text only when the skeleton is NOT visible (`rowCount == 0 && loading != true && !skeleton visible`). Without this the "No channels to show" text would flash while a freshly downloaded playlist is being turned into rows. `updateEmpty()` is already called from the `vm.loading` observer and right after `adapter.submitList(...)` in `rebuild()`, so no other call site was added.
  - `rowCount` is the number of ROWS of the last build, so a refresh while rows are on screen never brings the skeleton back. After `setSource`, `channels` becomes empty and the old rows are replaced by an empty list on the next rebuild; the skeleton then shows while the new source loads.
- Resource names now in use: layout `view_home_skeleton`; id `skeleton`; class `ShimmerView`. Uses existing `@dimen/hero_h`, `screen_pad`, `card_w_row`, `card_h_row`, `card_radius`, `@color/surface`, `@color/surface_focus`.
- Behaviour you will see: on first start (no saved playlist) the hero block and 3 rows of grey blocks with a light sweep appear under the header at once; when the rows are ready they replace it. With a saved playlist the skeleton is shown for a moment only (until the rows are built). It is not shown in the Browse tab (grid) or anywhere else.
- Limitations:
  - Checked by reading only (no Kotlin compiler or Android SDK was available here).
  - The 16 blocks each run their own animator, started in the same moment, so the sweeps are in step but not tied to one clock (a block attached later is a little out of phase).
  - The skeleton has the same hero + 3 x 5 shape on every screen size; it does not know how many rows the real list will have.

### Phase 24: "See all" for each category
- Changed: `K/BrowseFragment.kt`, `K/MainActivity.kt`, `K/HomeFragment.kt`, `R/layout/fragment_browse.xml`, `R/values/strings.xml` (2 strings appended), `API_MAP.md`. `K/HomeAdapter.kt` is UNCHANGED: it already calls `onSeeAll(row)` and shows the button only when `row.total > row.items.size`. No new library, color, dimen, drawable, manifest or Gradle change. No new file.
- `BrowseFragment`:
  - `companion object { fun newInstance(group: String?): BrowseFragment }` (private `ARG_GROUP = "group"`). The name goes into the fragment arguments (so it survives rotation); `null` gives no arguments = the normal grid. `BrowseFragment()` (used by the Browse tab) is unchanged.
  - `onCreate` reads the argument into `private var group: String?` (trimmed; blank = null = normal mode).
  - Category mode (`group != null`): `groupBar` (back button `btnBack` + `groupTitle` = the category name, style `Text.RowTitle`) is shown; `appTitle` ("Wosux Stream") and the whole `toolbar` (Source / Search / Favorites / Refresh) are hidden; `btnBack` click = `parentFragmentManager.popBackStack()`; on TV `btnBack` gets the focus (posted; no effect in touch mode).
  - The shared `vm.query` and `vm.favoritesOnly` are IGNORED in category mode (so "See all" always shows the whole category, whatever the Browse tab had filtered): `private val favOnly get() = group == null && vm.favoritesOnly` replaces the two `vm.favoritesOnly` reads in `onResume` and `toggleFavorite`; `applyFilter()` starts with `if (group != null) { applyGroup(group); return }`. The normal-mode code below it is byte-for-byte what it was.
  - `applyGroup(name)`: cancels the previous `groupJob`, then in `viewLifecycleOwner.lifecycleScope.launch` builds the list in `withContext(Dispatchers.Default) { filterGroup(all, name) }` and on the main thread sets `visible`, `groupReady = true`, `adapter.submitList(list)`, the count text (`channels_count`) and the empty text (`empty_group`). `vm.channels` changes call it again through the existing observer. `groupJob` is cancelled and `groupReady` reset in `onDestroyView`. The `vm.loading` observer shows the empty text only when `group == null || groupReady` (no flash while the list is being built).
  - Matching (`inGroup(c, name)`, no allocation per channel): split `c.group` on `;`, trim each part (whitespace), equal to `name` ignoring case = match. Whole part only (`News` does not match `Newsroom`). Playlist order is kept. Checked against `split(';') + trim + equalsIgnoreCase` on edge cases (`""`, `";"`, `"A;;B;"`, spaces, case, null) in a Python port: 0 differences. Note: `RowBuilder` groups case-sensitively ("News" and "news" are two rows), the grid matches ignoring case as the task says, so its count can be higher than `row.total` in that rare case.
  - Playing from the category grid: `ChannelStore.playlist = visible` (the category list), so UP / DOWN zaps inside the category.
  - New imports: `androidx.lifecycle.lifecycleScope`, `kotlinx.coroutines.{Dispatchers, Job, launch, withContext}`.
- `MainActivity`:
  - `fun openBrowse(group: String?)` (public, overload of the existing `openBrowse()` which still switches to the Browse tab): does nothing if the FragmentManager state is saved or a grid is already on the back stack; otherwise one `commit` (`setReorderingAllowed(true)`): `hide(<fragment of the current tab>)`, `add(R.id.container, BrowseFragment.newInstance(group), "group_browse")`, `addToBackStack("group_browse")`. The tab below is HIDDEN, not replaced, so its view is kept: BACK shows it again with the same vertical AND horizontal scroll positions.
  - BACK: the FragmentManager's own back-stack callback pops the grid (`backToHome` is disabled while the tab is Home, and the grid can only be opened from Home). The grid's back button does the same.
  - `selectTab(tab)` first does `popBackStackImmediate("group_browse", POP_BACK_STACK_INCLUSIVE)` when the back stack is not empty, so clicking a nav item while a grid is open drops the grid and then switches as before.
  - `onCreate`: when the activity is recreated (rotation / process restore) and the back stack is not empty, the grid is restored on top of the hidden tab by the FragmentManager; `selectTab` is then NOT called (it would show the tab under the grid): only `current`, `backToHome.isEnabled` and `updateNav()` are synced. Otherwise `selectTab(startTab)` as before.
  - New private constant `TAG_GROUP = "group_browse"` (fragment tag and back stack name). New imports: `androidx.fragment.app.FragmentManager`, `androidx.fragment.app.commit`.
- `HomeFragment`:
  - `onSeeAll = { row -> seeAll(row) }`. `seeAll(row)`: id starts with `group:` -> remembers `seeAllRowId = row.id` and calls `(activity as MainActivity).openBrowse(<id without "group:">)`; id `all` (the "Channels" row shown when the playlist has no categories) -> `openBrowse()` (Browse tab = every channel); any other id does nothing (`recents` / `favs` never show "See all").
  - `onHiddenChanged(hidden)`: when Home is shown again after a grid (`seeAllRowId != null`): clears it, `adapter.refreshCards()` + `rebuild()` (favorites toggled in the grid or in the player started from it show up in the hearts and in "My List"), and `focusSeeAll(id)`.
  - `focusSeeAll(rowId)` (TV): posted on `homeList`; finds the row's adapter position in `adapter.currentList`, then `findViewHolderForAdapterPosition(pos) as? HomeAdapter.RowVH` -> `b.rowMore.requestFocus()`. No effect in touch mode or when that row is not on screen.
  - Private top-level consts in HomeFragment.kt: `GROUP_PREFIX = "group:"`, `ALL_ROW_ID = "all"`.
- Resource names now in use: ids `groupBar`, `btnBack`, `groupTitle`, `appTitle`, `toolbar` (in `fragment_browse`); strings `back` ("◀ Back"), `empty_group` ("No channels in this category."). Uses existing `@style/Btn`, `@style/Text.RowTitle`.
- Behaviour you will see: "See all >" on a category row opens a grid of only that category with "◀ Back" and the category name on top; BACK (remote / system) or the button returns to Home exactly where it was; tapping a nav item while the grid is open switches tab as usual.
- Limitations:
  - Checked by reading only (no Kotlin compiler or Android SDK was available here); the matching logic was checked in a Python port, the XML for well-formedness, and every `R.string` / `R.id` / binding field used by name against the project files.
  - No search / favorites filter / source change inside the category grid (the button row is hidden on purpose). Phase 27 (inline search) can add search to it later.
  - The grid is in playlist order (the Home row shows channels with a logo first, the grid does not).
  - On a phone, rotation while a grid is open keeps the grid (FragmentManager restore); the grid's scroll position is not restored by the app.
