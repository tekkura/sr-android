package jp.oist.abcvlib.core.inputs

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.oist.abcvlib.core.inputs.publisher.PublisherRequirement
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublisherManagerRequirementTest {
    @Test
    fun publishersAreRequiredByDefault() {
        val manager = PublisherManager()
        val publisher = TestPublisher(context, manager)

        assertEquals(PublisherRequirement.REQUIRED, manager.getRequirement(publisher))
    }

    @Test
    fun publisherCanBeMarkedOptional() {
        val manager = PublisherManager()
        val publisher = TestPublisher(context, manager)

        manager.setRequirement(publisher, PublisherRequirement.OPTIONAL)

        assertEquals(PublisherRequirement.OPTIONAL, manager.getRequirement(publisher))
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class TestPublisher(
        context: Context,
        publisherManager: PublisherManager
    ) : Publisher<Subscriber>(context, publisherManager) {
        override fun getRequiredPermissions() = arrayListOf<String>()
    }
}
