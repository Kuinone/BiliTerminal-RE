package com.RobinNotBad.BiliClient.activity.search

import com.RobinNotBad.BiliClient.util.UiText

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

import android.text.TextUtils

import androidx.annotation.NonNull
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.adapter.SearchHistoryAdapter
import com.RobinNotBad.BiliClient.adapter.SearchSuggestionsAdapter
import com.RobinNotBad.BiliClient.api.SearchApi
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.TerminalDialog
import com.RobinNotBad.BiliClient.util.JsonUtil
import com.RobinNotBad.BiliClient.util.LinkUrlUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONArray
import org.json.JSONException

import java.util.Objects

class SearchActivity : InstanceActivity() {
    private var lastKeyword = "≠~`"
    private lateinit var historyRecyclerview: RecyclerView
    private lateinit var suggestionsRecyclerview: RecyclerView
    lateinit var searchHistoryAdapter: SearchHistoryAdapter
    lateinit var searchSuggestionsAdapter: SearchSuggestionsAdapter
    lateinit var viewPager: ViewPager2
    lateinit var keywordInput: EditText
    private lateinit var searchBar: ConstraintLayout

    /** 顶部 chrome（搜索框 + 排序条）是否展开。滚动收起时两者永远一起收，见 [applyTopChrome]。 */
    private var searchBarVisible = true

    // 搜索结果排序按钮（视频 / 专栏）。控件在本页而不是 fragment 里：fragment 的 ViewPager 只对齐到
    // 顶栏下方、搜索框压在 fragment 上面，控件放进去会被搜索框盖住（26.10.24 模拟器实测）。
    private lateinit var sortButton: Button

    /** 至少搜过一次才显示排序按钮：还没搜索时页面上是历史记录，给「排序」没有意义。 */
    private var hasSearched = false

    /** 当前分类支持排序（视频 / 专栏）且已搜过——只决定「要不要占位」，这里不看滚动与候选词浮层。 */
    private var sortBarEligible = false

    /** 搜索历史 / 建议浮层是否盖在列表上。浮层开着时排序条必须让位，否则压在候选词上面。 */
    private var dropdownVisible = false

    /** 顶部 chrome 的收缩/展开动画。两条 chrome 由它统一驱动，谁的时机都不许自己算。 */
    private var chromeAnimator: ValueAnimator? = null

    /** 控件「完整展开时」的高度：收起后量不到，第一次量到就缓存（与 RefreshListActivity 同一套做法）。 */
    private val naturalHeights = HashMap<View, Int>()

    /** 布局还没完成时量不到自然高度，此时重投一次；上限防止自旋。 */
    private var chromeRetry = 0
    private var refreshing = false
    private var animate_last: Long = 0
    lateinit var handler: Handler
    lateinit var searchHistory: ArrayList<String>
    lateinit var searchSuggestions: ArrayList<String>
    private var suggestionRunnable: Runnable? = null

    // 搜索建议的「代际令牌」：每次输入自增，异步返回的建议回调只认自己那一代。
    // 为什么需要：removeCallbacks 只能取消还没开始跑的延时任务，已经派发进 Thread 的请求取消不掉，
    // 慢响应返回时会覆盖新响应（建议乱序）；用令牌可以在回调落地时把过期结果整包丢掉。
    private var suggestionGeneration = 0
    private var suggestionsEnabled: Boolean = false
    private var defaultSearchContent: String? = null
    private var defaultSearchContentEnabled: Boolean = false

    var tutorial_show: Boolean = false
    var classname: String? = null

    // 搜索类别动态列表：根据用户设置动态管理启用的搜索类别及排序
    private lateinit var categoryList: ArrayList<String>

    // 默认搜索类别顺序（番剧紧跟视频，与上游一致）
    private val defaultCategoryOrder = arrayOf("video", "bangumi", "article", "user", "live")

    // 这两组是「关键词匹配表」，不是展示文案：它们只参与 keyword.contains 判定，
    // 一旦被 UiText 包成资源文案，判定键就跟着改名一起漂，彩蛋会静默失效。
    // 依据 docs/copy-inventory/STRINGS-MIGRATION.md §4「用作 key 的字符串不要迁移」。
    var specialList = arrayOf("心理疾病", "自杀", "自尽", "自残", "抑郁", "双相", "安眠药")
    var specialNamesList = arrayOf("严炜", "陈学峰", "徐波", "易德元", "舒微函", "张自东", "杨国明", "张俊胜")

    @SuppressLint("MissingInflatedId", "NotifyDataSetChanged", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        classname = javaClass.simpleName
        tutorial_show = SharedPreferencesUtil.getBoolean("tutorial_pager_$classname", true)

        asyncInflate(R.layout.activity_search) { _, _ ->
            // 教程改由 BaseActivity 按 Tutorials 注册表集中触发

            handler = Handler()

            suggestionsEnabled = SharedPreferencesUtil.getBoolean("search_suggestions_enable", true)
            defaultSearchContentEnabled = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SEARCH_DEFAULT_CONTENT_ENABLE, false)

            if (defaultSearchContentEnabled) {
                Thread {
                    try {
                        defaultSearchContent = SearchApi.getDefaultSearchContent()
                        if (defaultSearchContent != null && defaultSearchContent!!.isNotEmpty()) {
                            runOnUiThread { keywordInput.hint = defaultSearchContent }
                        }
                    } catch (e: Exception) {
                        Log.e("SearchActivity", "获取默认搜索内容失败", e)
                    }
                }.start()
            }

            viewPager = findViewById(R.id.viewPager)

            // 构建启用的搜索类别列表
            buildCategoryList()

            val searchBtn = findViewById<View>(R.id.search)
            keywordInput = findViewById(R.id.keywordInput)
            searchBar = findViewById(R.id.searchbar)
            historyRecyclerview = findViewById(R.id.history_recyclerview)
            suggestionsRecyclerview = findViewById(R.id.suggestions_recyclerview)

            viewPager.offscreenPageLimit = maxOf(1, categoryList.size - 1)
            viewPager.isUserInputEnabled = true

            keywordInput.onFocusChangeListener = View.OnFocusChangeListener { _, b ->
                if (b) {
                    val keyword = keywordInput.text.toString()
                    // 只有「输入框有内容 且 确实拿到了建议」才显示建议列表，
                    // 否则会出现建议列表空白、历史记录又看不见的情况
                    if (keyword.isEmpty() || !suggestionsEnabled || searchSuggestions.isEmpty()) {
                        showHistoryDropdown()
                    } else {
                        showSuggestionsDropdown()
                    }
                } else {
                    hideDropdowns()
                }
            }
            showHistoryDropdown()
            // 动态适配器：根据用户设置决定显示的搜索类别和顺序
            val vpfAdapter: FragmentStateAdapter = object : FragmentStateAdapter(this) {
                override fun getItemCount(): Int {
                    return categoryList.size
                }

                @NonNull
                override fun createFragment(position: Int): Fragment {
                    return createFragmentForCategory(categoryList[position])
                }
            }
            viewPager.adapter = vpfAdapter
            // 标题随类别页变化：搜索-视频 / 搜索-番剧 / 搜索-专栏 / 搜索-用户 / 搜索-直播
            updatePageName(viewPager.currentItem)

            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                private var lastPosition = -1

                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                    if (position != 0 && position != lastPosition) {
                        onScrolled(256)
                        if (tutorial_show) {
                            tutorial_show = false
                            findViewById<View>(R.id.text_tutorial_pager).visibility = View.GONE
                            SharedPreferencesUtil.putBoolean("tutorial_pager_$classname", false)
                        }
                        lastPosition = position
                    }
                    super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                }

                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    updatePageName(position)
                    val fragmentCurr = supportFragmentManager.findFragmentByTag("f$position")
                    if (fragmentCurr != null) {
                        (fragmentCurr as SearchFragment).refresh()
                    }
                    refreshSortBar()
                }
            })

            setupSortBar()

            searchBtn.setOnClickListener { searchKeyword(keywordInput.text.toString()) }
            searchBtn.setOnLongClickListener { jumpToTargetId(it) }
            keywordInput.setOnEditorActionListener { textView, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || event != null &&
                    KeyEvent.KEYCODE_ENTER == event.keyCode && KeyEvent.ACTION_DOWN == event.action) {
                    searchKeyword(keywordInput.text.toString())
                }
                false
            }
            // 首次点击输入框时，系统只会把焦点交还给输入框，不会自动拉起输入法
            // （全工程之前没有一处 showSoftInput），用户会觉得「点了没反应」。
            // 用 post 把拉起动作延后到本轮点击事件处理完之后：否则刚 grant 的焦点会被同一轮事件撤回，键盘仍然不弹。
            keywordInput.setOnClickListener { v ->
                v.post {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }
            }

            try {
                searchHistory = JsonUtil.jsonToArrayList(
                    JSONArray(SharedPreferencesUtil.getString(SharedPreferencesUtil.search_history, "[]")),
                    false)
            } catch (e: JSONException) {
                runOnUiThread { MsgUtil.err(e) }
                searchHistory = ArrayList()
            }
            searchHistoryAdapter = SearchHistoryAdapter(this, searchHistory)
            searchHistoryAdapter.setOnClickListener { position -> keywordInput.setText(searchHistory[position]) }
            searchHistoryAdapter.setOnLongClickListener { position ->
                MsgUtil.showMsg(UiText.of("删除成功"))
                searchHistory.removeAt(position)
                searchHistoryAdapter.notifyItemRemoved(position)
                searchHistoryAdapter.notifyItemRangeChanged(position, searchHistory.size - position)
                SharedPreferencesUtil.putString(SharedPreferencesUtil.search_history,
                    JSONArray(searchHistory).toString())
            }
            historyRecyclerview.layoutManager = CustomLinearManager(this)
            historyRecyclerview.adapter = searchHistoryAdapter
            if (searchHistory.size > 4) {
                historyRecyclerview.isFocusable = true
                historyRecyclerview.isFocusableInTouchMode = true
                historyRecyclerview.requestFocus()
            }

            searchSuggestions = ArrayList()
            searchSuggestionsAdapter = SearchSuggestionsAdapter(this, searchSuggestions)
            searchSuggestionsAdapter.setOnClickListener { position ->
                val suggestion = searchSuggestions[position]
                // 中文输入法还在组词（composing）状态时直接 setText，会把组合区的半成品和整词叠加，出现串字；
                // 先清掉组合态再整词替换
                keywordInput.clearComposingText()
                keywordInput.setText(suggestion)
                keywordInput.setSelection(suggestion.length)
                searchKeyword(suggestion)
            }
            suggestionsRecyclerview.layoutManager = CustomLinearManager(this)
            suggestionsRecyclerview.adapter = searchSuggestionsAdapter

            if (suggestionsEnabled) {
                keywordInput.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable) {
                        // searchKeyword() 内部也会 setText/clearFocus，会再次回调到这里；
                        // 不拦住就会在搜索执行过程中又发起一次建议请求
                        if (refreshing) return

                        val keyword = s.toString()

                        if (suggestionRunnable != null) {
                            handler.removeCallbacks(suggestionRunnable!!)
                        }

                        if (keyword.isEmpty()) {
                            runOnUiThread {
                                if (keywordInput.hasFocus()) {
                                    showHistoryDropdown()
                                }
                            }
                        } else {
                            // 每次输入自增代际令牌，回调只认自己那一代
                            suggestionGeneration++
                            val gen = suggestionGeneration
                            suggestionRunnable = Runnable {
                                Thread {
                                    try {
                                        val suggestions = SearchApi.getSearchSuggestions(keyword)
                                        runOnUiThread {
                                            // 过期请求直接丢弃（乱序的根因），页面已销毁也不再碰视图
                                            if (gen != suggestionGeneration || refreshing || isFinishing || isDestroyed) return@runOnUiThread
                                            searchSuggestions.clear()
                                            searchSuggestions.addAll(suggestions)
                                            searchSuggestionsAdapter.notifyDataSetChanged()

                                            if (suggestions.isNotEmpty()) {
                                                showSuggestionsDropdown()
                                            } else {
                                                showHistoryDropdown()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("SearchActivity", "获取搜索建议失败", e)
                                    }
                                }.start()
                            }
                            handler.postDelayed(suggestionRunnable!!, 300)
                        }
                    }
                })
            }

            val intentKeyword = intent.getStringExtra("keyword")
            if (intentKeyword != null) {
                findViewById<View>(R.id.top).setOnClickListener { finish() }
                keywordInput.setText(intentKeyword)
                MsgUtil.showMsg(UiText.of("可点击标题栏返回详情页"))
                // 从话题等「点一下就期待有结果」的入口进来时直接搜（26.10.19 话题点击落地），
                // 别让用户带着关键词停在空列表上再点一次放大镜。别处只传 keyword 的调用方行为不变。
                if (intent.getBooleanExtra("auto_search", false)) {
                    // 不能在这里直接调：onCreate 阶段 ViewPager 的 fragment 还没创建，
                    // searchKeyword 里的 findFragmentByTag 全是 null，关键词送不到任何一页，
                    // 用户看到的就是「有关键词但没结果」。等页面第一帧布局完成后再搜。
                    viewPager.post {
                        if (!isFinishing && !isDestroyed) searchKeyword(intentKeyword)
                    }
                }
            }
        }
    }

    fun jumpToTargetId(view: View): Boolean {
        val text = keywordInput.text.toString()
        LinkUrlUtil.handleId(this, text)
        return true
    }

    /**
     * 标题随搜索类别页变化（搜索-视频 / 搜索-番剧 / 搜索-专栏 / 搜索-用户 / 搜索-直播）。
     * 仅修改标题文字，不影响标题栏点击（菜单/返回详情）与长按搜索按钮等隐藏功能
     */
    private fun updatePageName(position: Int) {
        if (!::categoryList.isInitialized || position < 0 || position >= categoryList.size) return
        val sub = when (categoryList[position]) {
            "video" -> UiText.of("视频")
            "bangumi" -> UiText.of("番剧")
            "article" -> UiText.of("专栏")
            "user" -> UiText.of("用户")
            "live" -> UiText.of("直播")
            else -> categoryList[position]
        }
        setPageName("搜索-$sub")
    }

    /**
     * 根据用户偏好设置构建启用的搜索类别列表
     * 视频始终启用且在第一位；其他类别根据设置决定是否显示及排序
     */
    private fun buildCategoryList() {
        categoryList = ArrayList()

        // 读取已保存的排序配置
        val sortConf = SharedPreferencesUtil.getString(SharedPreferencesUtil.SEARCH_CATEGORY_SORT, "")

        // 旧逻辑要求保存的类别数必须与默认表完全相等，一旦默认表加了新类别（本次加「番剧」），
        // 旧配置就会整份作废，用户在设置页排好的顺序也随之失效（而设置页目前只能写 4 个类别）。
        // 改成：保存的每一项都是已知类别就采用它，默认表里新增、旧配置里还没有的类别补在后面。
        val savedKeys: List<String> = if (TextUtils.isEmpty(sortConf)) emptyList() else sortConf.split(";")
        val savedKeysUsable = savedKeys.isNotEmpty() && savedKeys.all { defaultCategoryOrder.contains(it) }

        if (savedKeysUsable) {
            // 使用已保存的排序顺序
            for (key in savedKeys) {
                if (isCategoryEnabled(key)) {
                    categoryList.add(key)
                }
            }
            // 补上保存配置里还没有的类别（版本升级新增、设置页尚未提供开关的 tab）
            for (key in defaultCategoryOrder) {
                if (!savedKeys.contains(key) && isCategoryEnabled(key)) {
                    categoryList.add(key)
                }
            }
        } else {
            // 使用默认排序顺序
            for (key in defaultCategoryOrder) {
                if (isCategoryEnabled(key)) {
                    categoryList.add(key)
                }
            }
        }

        // 确保视频搜索始终存在
        if (!categoryList.contains("video")) {
            categoryList.add(0, "video")
        }
    }

    /**
     * 判断指定搜索类别是否启用
     * 视频搜索始终启用
     */
    private fun isCategoryEnabled(categoryKey: String): Boolean {
        return when (categoryKey) {
            "video" -> true  // 视频始终启用
            "bangumi" -> SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SEARCH_CATEGORY_BANGUMI_SHOW, true)
            "article" -> SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SEARCH_CATEGORY_ARTICLE_SHOW, true)
            "user" -> SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SEARCH_CATEGORY_USER_SHOW, true)
            "live" -> SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SEARCH_CATEGORY_LIVE_SHOW, true)
            else -> false
        }
    }

    /**
     * 根据类别key创建对应的搜索Fragment
     */
    private fun createFragmentForCategory(categoryKey: String): Fragment {
        return when (categoryKey) {
            "video" -> SearchVideoFragment.newInstance()
            "bangumi" -> SearchBangumiFragment.newInstance()
            "article" -> SearchArticleFragment.newInstance()
            "user" -> SearchUserFragment.newInstance()
            "live" -> SearchLiveFragment.newInstance()
            else -> SearchVideoFragment.newInstance()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回后，重新加载分类列表以应用最新设置
        if (::categoryList.isInitialized && ::viewPager.isInitialized) {
            val oldSize = categoryList.size
            buildCategoryList()
            // 如果类别列表发生变化，通知适配器更新
            if (oldSize != categoryList.size) {
                viewPager.adapter?.notifyDataSetChanged()
                viewPager.offscreenPageLimit = maxOf(1, categoryList.size - 1)
            }
            // 类别顺序/数量可能变化，刷新标题子标题
            updatePageName(viewPager.currentItem)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun searchKeyword(str: String) {
        var keyword = str
        if (keyword.contains("Robin") || keyword.contains("robin")) {
            if (keyword.contains("撅")) {
                MsgUtil.showText(UiText.of("特殊彩蛋"), getString(R.string.egg_special))
                return
            }
            if (keyword.contains("纳西妲")) {
                MsgUtil.showText(UiText.of("特殊彩蛋"), getString(R.string.egg_robin_nahida))
                return
            }
        }
        for (s in specialList) {
            if (keyword.contains(s)) {
                MsgUtil.showText(UiText.of("特殊彩蛋"), getString(R.string.egg_warmwords_warmworld))
                return
            }
        }
        for (name in specialNamesList) {
            if (keyword.contains(name)) {
                MsgUtil.showText(UiText.of("特殊彩蛋"), getString(R.string.egg_special_names))
                return
            }
        }

        if (!refreshing) {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val curFocus: View?
            if (getCurrentFocus().also { curFocus = it } != null) {
                manager.hideSoftInputFromWindow(curFocus!!.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
            }

            // 搜索一旦真正执行，就要收起建议面板并作废还没返回的建议请求：
            // 否则那个请求返回后会把建议卡片重新盖在搜索结果上
            suggestionGeneration++
            if (suggestionRunnable != null) handler.removeCallbacks(suggestionRunnable!!)
            runOnUiThread { hideDropdowns() }

            if (keyword.isEmpty()) {
                if (defaultSearchContentEnabled && defaultSearchContent != null && defaultSearchContent!!.isNotEmpty()) {
                    keyword = defaultSearchContent!!
                } else {
                    runOnUiThread { MsgUtil.showMsg(UiText.of("还没输入内容喵~")) }
                    return
                }
            }

            hasSearched = true
            runOnUiThread { refreshSortBar() }

            if (Objects.equals(lastKeyword, keyword)) {
                runOnUiThread {
                    keywordInput.clearFocus()
                    hideDropdowns()
                }
            } else {
                refreshing = true
                lastKeyword = keyword

                runOnUiThread {
                    hideDropdowns()
                    keywordInput.clearFocus()
                }

                if (!searchHistory.contains(keyword)) {
                    try {
                        searchHistory.add(0, keyword)
                        SharedPreferencesUtil.putString(SharedPreferencesUtil.search_history,
                            JSONArray(searchHistory).toString())
                        runOnUiThread {
                            searchHistoryAdapter.notifyItemInserted(0)
                            searchHistoryAdapter.notifyItemRangeChanged(0, searchHistory.size)
                            historyRecyclerview.scrollToPosition(0)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { MsgUtil.err(e) }
                    }
                } else {
                    try {
                        val pos = searchHistory.indexOf(keyword)
                        searchHistory.remove(keyword)
                        searchHistory.add(0, keyword)
                        SharedPreferencesUtil.putString(SharedPreferencesUtil.search_history,
                            JSONArray(searchHistory).toString())
                        runOnUiThread {
                            searchHistoryAdapter.notifyItemMoved(pos, 0)
                            searchHistoryAdapter.notifyItemRangeChanged(0, searchHistory.size)
                            historyRecyclerview.scrollToPosition(0)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { MsgUtil.err(e) }
                    }
                }

                try {
                    for (i in 0 until categoryList.size) {
                        val fragmentById = supportFragmentManager.findFragmentByTag("f$i")
                        if (fragmentById != null)
                            (fragmentById as SearchFragment).update(keyword)
                    }
                    val fragmentCurr = supportFragmentManager
                        .findFragmentByTag("f" + viewPager.currentItem)
                    if (fragmentCurr != null) {
                        (fragmentCurr as SearchFragment).refresh()
                        requestFragmentFocus()
                    }
                } catch (e: Exception) {
                    report(e)
                }
                refreshing = false

                if (tutorial_show) {
                    runOnUiThread {
                        val textView = findViewById<TextView>(R.id.text_tutorial_pager)
                        textView.visibility = View.VISIBLE
                        textView.text = getString(R.string.tutorial_pager, categoryList.size - 1)
                    }
                }
            }
        }
    }

    /**
     * 绑定排序按钮。文案与点击行为按当前分类在 [refreshSortBar] 里填。
     */
    private fun setupSortBar() {
        sortButton = findViewById(R.id.searchSortButton)
        refreshSortBar()
    }

    /**
     * 按当前分类刷新排序按钮：支持排序的分类（视频 / 专栏）且已搜过才占位，其余不占。
     *
     * 这里只决定「该不该有这条 chrome」和它的文案；什么时候画出来、怎么动，一律交给
     * [applyTopChrome]——它和搜索框共用一条动画，两边各自跑就会错位。
     *
     * 被风控挡下（`SearchApi.RiskControlException`，wbi 与旧接口都没结果）时按钮改成
     * 「因B站风控不可用」，点它会提示「请稍候再试」**并真的重拉一次**（[SearchFragment.retry]）：
     * 风控态下下拉刷新是关的、同关键词再搜也会短路，不给一条真正的重试路径按钮就永久锁死了。
     */
    private fun refreshSortBar() {
        if (isFinishing || isDestroyed) return
        if (!::sortButton.isInitialized) return
        val fragment = supportFragmentManager.findFragmentByTag("f" + viewPager.currentItem) as? SearchFragment
        val options = fragment?.sortOptions().orEmpty()
        val eligibleBefore = sortBarEligible
        sortBarEligible = fragment != null && hasSearched && options.isNotEmpty()
        if (fragment == null || !sortBarEligible) {
            if (eligibleBefore || chromeAnimator == null) applyTopChrome(false)
            return
        }

        // 风控状态可能在后台线程变化，装一次回调让宿主跟着重画
        fragment.onSortStateChanged = { refreshSortBar() }

        if (fragment.isSortBlocked()) {
            sortButton.text = getString(R.string.text_search_sort_blocked)
            sortButton.setOnClickListener {
                MsgUtil.showMsg(getString(R.string.msg_search_sort_retry_later))
                // 不能只弹提示不干活：风控态下下拉刷新是关的、同关键词再搜也会短路，
                // 不给一条真正的重试路径的话按钮就永久锁死了（26.10.24 审查发现）
                fragment.retry()
            }
        } else {
            val current = options.firstOrNull { it.order == fragment.currentSortOrder() } ?: options[0]
            sortButton.text = getString(R.string.text_search_sort_button, getString(current.labelRes))
            sortButton.setOnClickListener { showSortDialog(fragment, options, current.order) }
        }
        // 只有「占位与否」变了才需要重排布局：文案变化不影响高度，而此刻若正跑着滚动动画，
        // 瞬间落定会把动画掐断（翻页时 onPageSelected 紧跟 onPageScrolled 就是这种时序）
        if (eligibleBefore != sortBarEligible || chromeAnimator == null) applyTopChrome(false)
    }

    /** 历史 / 建议浮层统一从这里开关：浮层盖在列表上时排序条必须让位，否则会压在候选词上面。 */
    private fun showHistoryDropdown() {
        historyRecyclerview.visibility = View.VISIBLE
        suggestionsRecyclerview.visibility = View.GONE
        setDropdownVisible(true)
    }

    private fun showSuggestionsDropdown() {
        historyRecyclerview.visibility = View.GONE
        suggestionsRecyclerview.visibility = View.VISIBLE
        setDropdownVisible(true)
    }

    private fun hideDropdowns() {
        historyRecyclerview.visibility = View.GONE
        suggestionsRecyclerview.visibility = View.GONE
        setDropdownVisible(false)
    }

    private fun setDropdownVisible(visible: Boolean) {
        if (dropdownVisible == visible) return
        dropdownVisible = visible
        applyTopChrome(false)
    }

    /**
     * 顶部 chrome（搜索框 + 排序条）的唯一显隐入口。
     *
     * 三条硬约定：
     * 1. **两者共用一条 [ValueAnimator]**、按同一个进度收敛。26.10.24 用户反馈「排序按钮消失/
     *    出现的时机和动画没跟随搜索框」，根因就是搜索框滑它的、按钮自己瞬间 GONE。
     * 2. **收的是真实高度，不是 `translationY`**：`ViewPager` 锚在排序条下面，translation 不参与
     *    布局，收起后列表不会补位，只会在动画结束后「啪」地跳一截。
     * 3. **浮层（历史 / 建议）开着时排序条只让出画面、不让出位置**（`INVISIBLE` 而非 `GONE`）：
     *    浮层正好盖在这条上，保持占位可以避免候选词一出现列表就整体上移。
     *
     * @param animate 滚动触发的收起 / 展开传 `true`；切分类、换关键词、浮层开关这类本身就瞬间
     *                发生（同帧还有列表重画）的传 `false`，动画反而会跟它们打架。
     */
    private fun applyTopChrome(animate: Boolean) {
        if (isFinishing || isDestroyed) return
        if (!::sortButton.isInitialized) return

        val expanded = searchBarVisible
        val sortSlot = sortBarEligible && expanded // 排序条在布局里是否占位
        val sortDrawn = sortSlot && !dropdownVisible // 是否真的画出来

        if (!animate) {
            val running = chromeAnimator
            chromeAnimator = null
            running?.cancel()
            searchBar.alpha = 1f
            sortButton.alpha = 1f
            resetViewHeight(searchBar)
            resetViewHeight(sortButton)
            searchBar.visibility = if (expanded) View.VISIBLE else View.GONE
            sortButton.visibility = when {
                !sortSlot -> View.GONE
                sortDrawn -> View.VISIBLE
                else -> View.INVISIBLE
            }
            return
        }

        val searchNatural = naturalHeightOf(searchBar)
        if (searchNatural <= 0) {
            // 布局还没完成、量不到自然高度：下一帧再来（有次数上限，不会自旋）
            if (chromeRetry++ < 3) searchBar.post { applyTopChrome(true) }
            return
        }
        chromeRetry = 0
        // 注意：这里按「是否支持排序」取自然高度，而不是按 `sortSlot`——收起时 `expanded` 已经是
        // false，用 sortSlot 判断会让排序条无法参与动画，只能在动画结束那一刻直接消失。
        val sortNatural = if (sortBarEligible) naturalHeightOf(sortButton) else 0
        val animateSort = sortNatural > 0

        val running = chromeAnimator
        chromeAnimator = null
        running?.cancel()

        // 起点必须在改可见性/高度**之前**读：`View.getHeight()` 是上一次布局的结果，
        // 先 set 再读会拿到旧值，动画就成了「原地不动」。
        val searchFrom = if (searchBar.visibility == View.VISIBLE) searchBar.height else 0
        val sortFrom = if (sortButton.visibility != View.GONE) sortButton.height else 0
        val searchTo = if (expanded) searchNatural else 0
        val sortTo = if (sortSlot) sortNatural else 0

        // 展开时先把要出现的那个摆成「可见 + 起点高度 + 透明」，否则会从 GONE 直接跳到满高
        if (expanded && searchBar.visibility != View.VISIBLE) {
            searchBar.visibility = View.VISIBLE
            searchBar.alpha = 0f
        }
        setViewHeight(searchBar, searchFrom)
        if (animateSort) {
            if (sortTo > 0 && sortButton.visibility != View.VISIBLE) {
                sortButton.visibility = View.VISIBLE
                sortButton.alpha = 0f
            }
            setViewHeight(sortButton, sortFrom)
        }

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = CHROME_ANIM_MS
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedFraction
            val shown = (searchFrom + (searchTo - searchFrom) * fraction).toInt()
            setViewHeight(searchBar, shown)
            searchBar.alpha = (shown.toFloat() / searchNatural).coerceIn(0f, 1f)
            if (animateSort) {
                val sortShown = (sortFrom + (sortTo - sortFrom) * fraction).toInt()
                setViewHeight(sortButton, sortShown)
                sortButton.alpha = (sortShown.toFloat() / sortNatural).coerceIn(0f, 1f)
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // 被新手势取消掉的旧动画不要收尾，否则会把新状态改回旧的
                if (chromeAnimator !== animation) return
                chromeAnimator = null
                searchBar.alpha = 1f
                sortButton.alpha = 1f
                resetViewHeight(searchBar)
                resetViewHeight(sortButton)
                searchBar.visibility = if (expanded) View.VISIBLE else View.GONE
                sortButton.visibility = when {
                    !sortSlot -> View.GONE
                    sortDrawn -> View.VISIBLE
                    else -> View.INVISIBLE
                }
            }
        })
        chromeAnimator = animator
        animator.start()
    }

    /** 把控件高度改成指定值（保留其它 LayoutParams）。 */
    private fun setViewHeight(view: View, height: Int) {
        val lp = view.layoutParams ?: return
        if (lp.height == height) return
        lp.height = height
        view.layoutParams = lp
    }

    /** 还原成 `wrap_content` + 完全不透明。 */
    private fun resetViewHeight(view: View) {
        val lp = view.layoutParams ?: return
        if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            view.layoutParams = lp
        }
    }

    /**
     * 控件「完整展开时」的高度。收起后量不到，所以第一次量到就缓存；只在
     * `wrap_content`（没被动画压过）时采信，避免把动画中途的中间值记成自然高度。
     */
    private fun naturalHeightOf(view: View): Int {
        val cached = naturalHeights[view]
        if (cached != null && cached > 0) return cached
        if (view.layoutParams?.height != ViewGroup.LayoutParams.WRAP_CONTENT) return 0
        val measured = if (view.height > 0) view.height else view.measuredHeight
        if (measured > 0) naturalHeights[view] = measured
        return measured
    }

    /**
     * 排序选择弹窗：与番剧选集（`BangumiInfoFragment.getEposideChooseDialog`）同一款
     * `TerminalDialog.singleChoice`——带 `▸` 引导符的单选列表，选中项即当前排序。
     *
     * 这个弹窗**不会自动关闭**（选集场景要在回调里做越界校验），所以这里自己 `dismiss()`。
     */
    private fun showSortDialog(
        fragment: SearchFragment,
        options: List<SearchSortOption>,
        currentOrder: String
    ) {
        val labels = options.map { getString(it.labelRes) }
        val checked = options.indexOfFirst { it.order == currentOrder }.coerceAtLeast(0)
        // TerminalDialog 只负责拼装，**不会自己 show**（与番剧选集同一约定），这里必须调 .show()
        TerminalDialog.singleChoice(
            context = this,
            title = getString(R.string.text_search_sort_title),
            items = labels,
            checked = checked
        ) { sheet, which ->
            options.getOrNull(which)?.let { fragment.selectSort(it.order) }
            sheet.dialog.dismiss()
            refreshSortBar()
        }.show()
    }

    fun onScrolled(dy: Int) {
        if (System.currentTimeMillis() - animate_last > 200) {
            if (dy > 0 && searchBarVisible) {
                animate_last = System.currentTimeMillis()
                searchBarVisible = false
                applyTopChrome(true)
            }
            if (dy < -1 && !searchBarVisible) {
                animate_last = System.currentTimeMillis()
                searchBarVisible = true
                applyTopChrome(true)
            }
        }

        requestFragmentFocus()
    }

    private fun requestFragmentFocus() {
        // 输入框已经持有焦点时就不要再抢：键盘弹出会改变布局并触发列表滚动回调，
        // 而滚动回调（onScrolled）每次都会走到这里，把刚拉起的键盘又顶掉
        if (keywordInput.hasFocus()) return
        val fragmentCurr = supportFragmentManager
            .findFragmentByTag("f" + viewPager.currentItem)
        if (fragmentCurr != null) {
            (fragmentCurr as SearchFragment).refresh()
            if (fragmentCurr.view != null) {
                val recyclerView = fragmentCurr.view!!.findViewById<View>(R.id.recyclerView)
                recyclerView.isFocusable = true
                recyclerView.isFocusableInTouchMode = true
                recyclerView.requestFocus()
            }
        }
    }

    private companion object {
        /**
         * 顶部 chrome 收起 / 展开的时长。与旧实现里「200ms 后置 GONE」对齐，
         * 手表上再长就会觉得「按了没反应」。
         */
        const val CHROME_ANIM_MS = 200L
    }
}