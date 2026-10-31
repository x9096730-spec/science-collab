/*
 * Science Collab: syncs a board with a Matrix room through hidden custom events.
 * In encrypted rooms such events are encrypted like ordinary messages.
 */

package im.vector.app.features.home

import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings

internal class ScBoardSync(
        private val session: Session,
        private val roomId: String,
        private val onSnapshots: (List<ScBoard>) -> Unit,
) {

    private var timeline: Timeline? = null
    private var pages = 0
    private val seen = HashSet<String>()

    /** Starts reading the room history and live updates. Returns false when the room is not found. */
    fun start(): Boolean {
        val room = session.roomService().getRoom(roomId) ?: return false
        val t = room.timelineService().createTimeline(null, TimelineSettings(60))
        timeline = t
        t.addListener(object : Timeline.Listener {
            override fun onTimelineUpdated(snapshot: List<TimelineEvent>) {
                handle(snapshot)
            }
        })
        t.start()
        return true
    }

    private fun handle(snapshot: List<TimelineEvent>) {
        val boards = ArrayList<ScBoard>()
        for (event in snapshot) {
            val root = event.root
            if (root.getClearType() != TYPE) continue
            if (!seen.add(event.eventId)) continue
            val raw = root.getClearContent()?.get("board") as? String ?: continue
            val board = ScBoards.fromJsonString(raw) ?: continue
            boards.add(board)
        }
        if (boards.isNotEmpty()) onSnapshots(boards)
        // look a few pages further back for older snapshots
        if (pages < MAX_PAGES) {
            pages++
            timeline?.paginate(Timeline.Direction.BACKWARDS, 60)
        }
    }

    /** Sends the current state of the board to the room. */
    fun publish(board: ScBoard): Boolean {
        val room = session.roomService().getRoom(roomId) ?: return false
        val content = mapOf("v" to 1, "board" to ScBoards.toJsonString(board, true))
        room.sendService().sendEvent(TYPE, content)
        return true
    }

    fun stop() {
        timeline?.dispose()
        timeline = null
    }

    companion object {
        const val TYPE = "org.sciencecollab.board.snapshot"
        private const val MAX_PAGES = 4
    }
}
