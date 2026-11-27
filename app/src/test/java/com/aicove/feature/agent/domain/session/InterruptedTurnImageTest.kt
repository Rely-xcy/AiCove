package com.aicove.feature.agent.domain.session

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicove.feature.agent.data.local.database.AgentDatabase
import com.aicove.feature.agent.domain.model.AgentImage
import com.aicove.feature.agent.domain.model.AgentMessage
import com.aicove.feature.agent.presentation.AgentAttachment
import com.aicove.feature.agent.presentation.MessageRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class InterruptedTurnImageTest {
    @get:Rule
    val temp = TemporaryFolder()

    companion object {
        private const val ORIGINAL_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP44GkFAAOgAXTkhGdpAAAAAElFTkSuQmCC"
        private const val DECOY_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGPgU7wLAAFNAQ1K/lk2AAAAAElFTkSuQmCC"

        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue("Robolectric SQLite 需要标准 Linux/CI 环境", !androidContainer)
        }
    }

    @Test
    fun recoveryMatchesHistoryWhileLocalCopyExistsAndSkipsItOnceMissing() = runBlocking {
        val copies = temp.newFolder("copies")
        val workspace = temp.newFolder("workspace")
        val originalBytes = Base64.getDecoder().decode(ORIGINAL_PNG)
        val local = File(copies, "plot.png").apply { writeBytes(originalBytes) }
        val lost = File(copies, "lost.png").apply { writeBytes(originalBytes) }
        val decoy = File(workspace, lost.name).apply { writeBytes(Base64.getDecoder().decode(DECOY_PNG)) }
        val note = File(copies, "notes.txt").apply { writeText("Not an image") }

        fun attachment(file: File, mime: String, isImage: Boolean) = AgentAttachment(
            fileName = file.name,
            containerPath = File(workspace, file.name).absolutePath,
            localPath = file.absolutePath,
            mimeType = mime,
            sizeBytes = file.length(),
            isImage = isImage
        )
        val keptImage = attachment(local, "image/png", isImage = true)
        val attachments = listOf(
            attachment(lost, "image/png", isImage = true),
            attachment(note, "text/plain", isImage = false),
            keptImage
        )

        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AgentDatabase::class.java
        ).build()
        try {
            val dao = db.agentMessageDao()
            val messages = MessagePersistenceUseCase(dao, db)
            messages.persist("session", MessageRole.USER, "inspect the plot", id = "user", attachments = attachments)
            assertTrue(lost.delete())
            assertTrue(decoy.isFile)
            val stored = checkNotNull(dao.getMessageById("user")).toUIMessage()
            val replay = messages.buildHistory("session", "__pending__").single() as AgentMessage.UserMessage
            assertEquals(listOf(AgentImage("image/png", ORIGINAL_PNG, keptImage.containerPath)), replay.images)

            val recovery = MessagePersistenceUseCase(dao, db)
            assertEquals(replay.images, recovery.recoverInputImages(stored.attachments))

            assertTrue(local.delete())
            assertEquals(emptyList<AgentImage>(), recovery.recoverInputImages(stored.attachments))
        } finally {
            db.close()
        }
    }
}
