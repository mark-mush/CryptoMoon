package com.rmnivnv.cryptomoon.ui.coins

import android.content.Context
import com.rmnivnv.cryptomoon.R
import com.rmnivnv.cryptomoon.model.*
import com.rmnivnv.cryptomoon.model.db.CMDatabase
import com.rmnivnv.cryptomoon.model.rxbus.CoinsLoadingEvent
import com.rmnivnv.cryptomoon.model.rxbus.MainCoinsListUpdatedEvent
import com.rmnivnv.cryptomoon.model.rxbus.OnDeleteCoinsMenuItemClickedEvent
import com.rmnivnv.cryptomoon.model.rxbus.RxBus
import com.rmnivnv.cryptomoon.model.network.NetworkRequests
import com.rmnivnv.cryptomoon.utils.*
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject

/**
 * Created by rmnivnv on 11/07/2017.
 */
class CoinsPresenter @Inject constructor(private val context: Context,
                                         private val view: ICoins.View,
                                         private val networkRequests: NetworkRequests,
                                         private val coinsController: CoinsController,
                                         private val db: CMDatabase,
                                         private val resProvider: ResourceProvider,
                                         private val pageController: PageController,
                                         private val multiSelector: MultiSelector,
                                         private val holdingsHandler: HoldingsHandler) : ICoins.Presenter {

    private val disposable = CompositeDisposable()
    private var coins: ArrayList<DisplayCoin> = ArrayList()
    private var isRefreshing = false
    private var isFirstStart = true

    override fun onCreate(coins: ArrayList<DisplayCoin>) {
        this.coins = coins
        subscribeToObservables()
        getAllCoinsInfo()
    }

    private fun subscribeToObservables() {
        addCoinsChangesObservable()
        addHoldingsChangesObservable()
        setupRxBusEventsListeners()
        addOnPageChangedObservable()
    }

    private fun addCoinsChangesObservable() {
        disposable.add(db.displayCoinsDao().getAllCoins()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ onCoinsFromDbUpdates(it) }))
    }

    private fun onCoinsFromDbUpdates(list: List<DisplayCoin>) {
        if (list.isNotEmpty()) {
            coins.clear()
            coins.addAll(list)
            coins.sortBy { it.from }
            view.updateRecyclerView()
            if (isFirstStart) {
                isFirstStart = false
                updatePrices()
            }
        }
    }

    private fun addHoldingsChangesObservable() {
        disposable.add(db.holdingsDao().getAllHoldings()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ onHoldingsUpdate(it) }))
    }

    private fun onHoldingsUpdate(holdings: List<HoldingData>) {
        if (holdings.isNotEmpty()) {
            view.enableTotalHoldings()
            setTotalHoldingValue(holdings)
            setTotalHoldingsChangePercent(holdings)
        } else {
            view.disableTotalHoldings()
        }
    }

    private fun setTotalHoldingValue(holdings: List<HoldingData>) {
        view.setTotalHoldingsValue("$ ${getStringWithTwoDecimalsFromDouble(holdingsHandler.getTotalValueWithCurrentPrice(holdings))}")
    }

    private fun setTotalHoldingsChangePercent(holdings: List<HoldingData>) {
        val totalChangePercent = holdingsHandler.getTotalChangePercent(holdings)
        val sign = if (totalChangePercent >= 0) "+" else "-"
        view.setTotalHoldingsChangePercent("$sign${getStringWithTwoDecimalsFromDouble(totalChangePercent)}%")
        view.setTotalHoldingsChangePercentColor(getChangeColor(totalChangePercent))
    }

    private fun setupRxBusEventsListeners() {
        disposable.add(RxBus.listen(OnDeleteCoinsMenuItemClickedEvent::class.java)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { onDeleteClicked() })
    }

    private fun onDeleteClicked() {
        val coinsToDelete = coins.filter { it.selected }
        if (coinsToDelete.isNotEmpty()) {
            val toast = if (coinsToDelete.size > 1) resProvider.getString(R.string.coins_deleted)
            else resProvider.getString(R.string.coin_deleted)
            coinsController.deleteDisplayCoins(coinsToDelete)
            context.toastShort(toast)
            multiSelector.atLeastOneIsSelected = false
            RxBus.publish(MainCoinsListUpdatedEvent())
        }
    }

    private fun addOnPageChangedObservable() {
        disposable.add(pageController.getPageObservable()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { onPageChanged(it) })
    }

    private fun onPageChanged(position: Int) {
        if (position != COINS_FRAGMENT_PAGE_POSITION) {
            disableSelected()
        }
    }

    private fun disableSelected() {
        if (multiSelector.atLeastOneIsSelected) {
            coins.forEach { if (it.selected) it.selected = false }
            view.updateRecyclerView()
            multiSelector.atLeastOneIsSelected = false
        }
    }

    private fun getAllCoinsInfo() {
        disposable.add(networkRequests.getAllCoins(object : GetAllCoinsCallback {
            override fun onSuccess(allCoins: ArrayList<InfoCoin>) {
                if (allCoins.isNotEmpty()) {
                    coinsController.saveAllCoinsInfo(allCoins)
                }
            }

            override fun onError(t: Throwable) {

            }
        }))
    }

    override fun onViewCreated() {

    }

    override fun onStart() {
        if (coins.isNotEmpty()) updatePrices()
    }

    private fun updatePrices() {
        val queryMap = createCoinsMapWithCurrencies(coins)
        if (queryMap.isNotEmpty()) {
            RxBus.publish(CoinsLoadingEvent(true))
            disposable.add(networkRequests.getPrice(queryMap, object : GetPriceCallback {
                override fun onSuccess(coinsInfoList: ArrayList<DisplayCoin>?) {
                    if (coinsInfoList != null && coinsInfoList.isNotEmpty()) {
                        coinsController.saveDisplayCoinList(filterList(coinsInfoList))
                    }
                    afterRefreshing()
                }

                override fun onError(t: Throwable) {
                    afterRefreshing()
                }
            }))
        }
    }

    private fun filterList(coinsInfoList: ArrayList<DisplayCoin>): ArrayList<DisplayCoin> {
        val result: ArrayList<DisplayCoin> = ArrayList()
        coins.forEach { (from, to) ->
            val find = coinsInfoList.find { it.from == from && it.to == to }
            if (find != null) result.add(find)
        }
        return result
    }

    private fun afterRefreshing() {
        RxBus.publish(CoinsLoadingEvent(false))
        if (isRefreshing) {
            view.hideRefreshing()
            isRefreshing = false
            view.enableSwipeToRefresh()
        }
    }

    override fun onDestroy() {
        disposable.clear()
    }

    override fun onStop() {
        disableSelected()
    }

    override fun onSwipeUpdate() {
        disableSelected()
        isRefreshing = true
        updatePrices()
    }

    override fun onCoinClicked(coin: DisplayCoin) {
        view.startCoinInfoActivity(coin.from, coin.to)
    }
}