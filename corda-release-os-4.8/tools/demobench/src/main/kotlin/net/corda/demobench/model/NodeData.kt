package net.corda.demobench.model

import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleListProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections.observableArrayList
import net.corda.worldmap.CityDatabase
import tornadofx.*
import java.util.*

object SuggestedDetails {
    private val banks = listOf(
            // Mike:  Rome? Why Rome?
            // Roger: Notaries public (also called "notaries", "notarial officers", or "public notaries") hold an office
            //        which can trace its origins back to the ancient Roman Republic, when they were called scribae ("scribes"),
            //        tabelliones forenses, or personae publicae.[4]
            // Mike:  Can't argue with that. It's even got a citation.
            "Notary" to "Rome",
            "Bank of Breakfast Tea" to "Liverpool",
            "Bank of Big Apples" to "New York",
            "Bank of Baguettes" to "Paris",
            "Bank of Fondue" to "Geneve",
            "Bank of Maple Syrup" to "Toronto",
            "Bank of Golden Gates" to "San Francisco"
    )

    private var cursor = 0

    fun nextBank(exists: (String) -> Boolean): Pair<String, String> {
        for (i in banks.indices) {
            val bank = banks[cursor]
            if (!exists(bank.first)) {
                return bank
            }
            cursor = (cursor + 1) % banks.size
        }
        return banks[cursor]
    }

    fun reset() {
        cursor = 0
    }
}

class NodeData {
    val legalName = SimpleStringProperty("")
    val nearestCity = SimpleObjectProperty(CityDatabase["London"]!!)
    val p2pPort = SimpleIntegerProperty()
    val rpcPort = SimpleIntegerProperty()
    val rpcAdminPort = SimpleIntegerProperty()
    val webPort = SimpleIntegerProperty()
    val h2Port = SimpleIntegerProperty()
    val extraServices = SimpleListProperty(observableArrayList<ExtraService>())
}

class NodeDataModel : ItemViewModel<NodeData>(NodeData()) {
    val legalName = bind { item?.legalName }
    val nearestCity = bind { item?.nearestCity }
    val p2pPort = bind { item?.p2pPort }
    val rpcPort = bind { item?.rpcPort }
    val rpcAdminPort = bind { item?.rpcAdminPort }
    val webPort = bind { item?.webPort }
    val h2Port = bind { item?.h2Port }
}

interface ExtraService

data class CurrencyIssuer(val currency: Currency) : ExtraService {
    override fun toString(): String = "Issuer $currency"
}
