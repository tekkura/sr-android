package jp.oist.abcvlib.rosbridge

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import jp.oist.abcvlib.rosbridge.databinding.ActivityMainBinding
import jp.oist.abcvlib.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : AppCompatActivity(), RosBridgeClientListener {
    private val rosBridgeClient: RosBridgeClient = RosBridgeClient(this)
    private lateinit var binding: ActivityMainBinding

    @Volatile
    private var connected = false

    @Volatile
    private var subscribed = false

    @Volatile
    private var published = false

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonConnect.setOnClickListener {
            val ip = binding.rosPcIp.text.toString().trim()
            if (ip.isNotBlank()) {
                binding.connectionIndicator.show()
                rosBridgeClient.connect(ip)
            } else {
                binding.rosPcIp.error = getString(R.string.ros_ip_required)
                binding.rosPcIp.requestFocus();
            }
        }

        binding.buttonPublish.setOnClickListener {
            // publish to /test_from_android
            // PC: ros2 topic echo /test_from_android std_msgs/msg/String
            val topic = "/test_from_android"
            val message = binding.rosMessage.text.trim().toString()
            lifecycleScope.launch(Dispatchers.IO) {
                published = rosBridgeClient.publish(topic, message)
            }
            binding.textPublishTo.text = getString(R.string.ros_publish_to, topic)

            // Hide keyboard
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.rosMessage.windowToken, 0)
        }

        binding.textStatus.setOnClickListener {
            rosBridgeClient.disconnect()
        }

        binding.test.setOnClickListener {
            val connect = if (connected) "OK" else "FAIL"
            val subscribe = if (subscribed) "OK" else "SKIP"
            val publish = if (published) "OK" else "SKIP"
            val pass = if (connected && subscribed && published) "PASS" else "FAIL"
            val message = "$pass: connect=$connect subscribe=$subscribe publish=$publish "
            binding.testResult.text = message
            if (pass == "PASS")
                Logger.i("SmokeTest", message)
            else
                Logger.e("SmokeTest", message)
        }

        binding.layoutConnected.hide()
        binding.connectionIndicator.hide()
        binding.textReceived.movementMethod = ScrollingMovementMethod()
    }


    override fun onConnected(url: String) {
        connected = true
        lifecycleScope.launch(Dispatchers.Main) {
            binding.textStatus.text = getString(R.string.ros_status, url)
            binding.layoutConnected.show()
            binding.layoutDisconnected.hide()
            binding.connectionIndicator.hide()

            // subscribe to "test_from_ros" topic
            // PC: ros2 topic pub /test_from_ros std_msgs/msg/String "data: 'hello from ros'" --rate 1
            val topic = "/test_from_ros"
            withContext(Dispatchers.IO) {
                rosBridgeClient.subscribe(topic)
            }
            binding.textSubscribeTo.text = getString(R.string.ros_subscribe_to, topic)
            binding.testResult.text = ""
        }
    }

    override fun onMessage(topic: String, message: String) {
        subscribed = true
        lifecycleScope.launch(Dispatchers.Main) {
            binding.textReceived.append("$message\n")
        }
    }

    override fun onDisconnected() {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.layoutConnected.hide()
            binding.layoutDisconnected.show()
            binding.textReceived.text = ""
            binding.testResult.text = ""
            binding.rosMessage.setText("")
        }
        connected = false
        subscribed = false
        published = false
    }

    override fun onError(error: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.connectionIndicator.hide()
            binding.testResult.text = ""
            Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
        }
        connected = false
        subscribed = false
        published = false
    }

    override fun onDestroy() {
        rosBridgeClient.disconnect()
        super.onDestroy()
    }

    private fun View.show() {
        this.visibility = View.VISIBLE
    }

    private fun View.hide() {
        this.visibility = View.GONE
    }
}


