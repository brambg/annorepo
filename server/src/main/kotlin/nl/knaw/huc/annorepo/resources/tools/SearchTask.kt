package nl.knaw.huc.annorepo.resources.tools

import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.joda.time.Instant
import org.slf4j.LoggerFactory
import nl.knaw.huc.annorepo.api.SearchStatusSummary

abstract class SearchTask(queryMap: HashMap<*, *>) : Runnable {

    class Status(private val queryMap: HashMap<*, *>) {

        var state = State.CREATED
        val annotations: MutableList<Map<String, Any>> = mutableListOf()
        var startTime: Instant = Instant.now()
        var endTime: Instant? = null
        var totalContainersToSearch: Int = 0
        val containersSearched: AtomicInteger = AtomicInteger(0)
        val errors: MutableList<String> = mutableListOf()

        fun summary(): SearchStatusSummary = SearchStatusSummary(
            query = queryMap,
            startedAt = startTime.toDate(),
            finishedAt = endTime?.toDate(),
            expiresAfter = expirationTime(),
            state = state.name,
            totalContainersToSearch = totalContainersToSearch,
            containersSearched = containersSearched.get(),
            hitsFoundSoFar = annotations.size,
            errors = errors,
            processingTimeInMillis = (endTime?.millis ?: Instant.now().millis) - startTime.millis
        )

        private val timeToLive = TimeUnit.HOURS.toMillis(1)

        fun expirationTime(): Date? = endTime?.withDurationAdded(timeToLive, 1)?.toDate()
    }

    enum class State {
        CREATED, RUNNING, DONE, FAILED
    }

    private val log = LoggerFactory.getLogger(javaClass)

    val id: String = UUID.randomUUID().toString()
    val status = Status(queryMap)

    override fun run() {
        status.state = State.RUNNING
        status.startTime = Instant.now()
        try {
            runSearch(status)
            status.state = State.DONE
        } catch (t: Throwable) {
            t.printStackTrace()
            status.state = State.FAILED
            status.errors += "${t.javaClass}: ${t.message ?: ""}"
        }
        status.endTime = Instant.now()
        log.debug("query done")
    }

    abstract fun runSearch(status: Status)
}
