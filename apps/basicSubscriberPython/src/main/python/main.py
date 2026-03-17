from java import dynamic_proxy
from jp.oist.abcvlib.util import SerialCommManager
from jp.oist.abcvlib.core.inputs import PublisherManager
from jp.oist.abcvlib.core.inputs.microcontroller import (
    BatteryData, WheelData,
    BatteryDataSubscriber,
    WheelDataSubscriber
)
from jp.oist.abcvlib.core.inputs.phone import (
    OrientationData, OrientationDataSubscriber,
    MicrophoneData, MicrophoneDataSubscriber,
    ObjectDetectorData, ObjectDetectorDataSubscriber,
    QRCodeData, QRCodeDataSubscriber
)

context = None # Activity context
speed = 0.0
increment = 0.4

class BatterySubscriber(dynamic_proxy(BatteryDataSubscriber)):
    def onBatteryVoltageUpdate(self, timestamp, battery_voltage):
        context.guiUpdater.setBatteryVoltage(battery_voltage)

    def onChargerVoltageUpdate(self, timestamp, charger_voltage, coil_voltage):
        context.guiUpdater.setChargerVoltage(charger_voltage)
        context.guiUpdater.setCoilVoltage(coil_voltage)


class WheelSubscriber(dynamic_proxy(WheelDataSubscriber)):
    def onWheelDataUpdate(self, timestamp, wheel_count_l, wheel_count_r,
                          wheel_distance_l, wheel_distance_r,
                          wheel_speed_instant_l, wheel_speed_instant_r,
                          wheel_speed_buffered_l, wheel_speed_buffered_r,
                          wheel_speed_exp_avg_l, wheel_speed_exp_avg_r
                          ):
        left = f"{wheel_count_l} : {wheel_distance_l:.2f} : {wheel_speed_instant_l:.2f} : {wheel_speed_buffered_l:.2f} : {wheel_speed_exp_avg_l:.2f}"
        right = f"{wheel_count_r} : {wheel_distance_r:.2f} : {wheel_speed_instant_r:.2f} : {wheel_speed_buffered_r:.2f} : {wheel_speed_exp_avg_r:.2f}"
        context.guiUpdater.setWheelLeftData(left)
        context.guiUpdater.setWheelRightData(right)


class OrientationSubscriber(dynamic_proxy(OrientationDataSubscriber)):
    def onOrientationUpdate(self, timestamp, theta_rad, angular_velocity_rad):
        theta_deg = OrientationData.Companion.getThetaDeg(theta_rad)
        angular_velocity_deg = OrientationData.Companion.getAngularVelocityDeg(angular_velocity_rad)
        context.guiUpdater.setThetaDeg(theta_deg)
        context.guiUpdater.setAngularVelocityDeg(angular_velocity_deg)


class MicrophoneSubscriber(dynamic_proxy(MicrophoneDataSubscriber)):
    def onMicrophoneDataUpdate(self, audio_data, num_samples, sample_rate, start_time, end_time):
        audio_data_string = ", ".join(f"{v:.1E}" for v in audio_data[0:5])
        context.guiUpdater.setAudioDataString(audio_data_string)


class ObjectDetectorSubscriber(dynamic_proxy(ObjectDetectorDataSubscriber)):
    def onObjectsDetected(self, bitmap, image, results, inference_time, height, width):
        try:
            category = results.get(0).getCategories().get(0)
            try:label = category.getLabel() # tensorflow
            except:
                try:label = category.getCategoryName() # mediapipe
                except:label = "unknown"
            context.guiUpdater.setObjectDetectorString(label)
        except:
            context.guiUpdater.setObjectDetectorString("no objects detected")


class QRCodeSubscriber(dynamic_proxy(QRCodeDataSubscriber)):
    def onQRCodeDetected(self, qr_data_decoded):
        context.guiUpdater.setQrDataString(qr_data_decoded)

def setup():
    publisher_manager = PublisherManager()

    battery_data = BatteryData.Builder(context, publisher_manager).build()
    battery_data.addSubscriber(BatterySubscriber())

    wheel_data = WheelData.Builder(context, publisher_manager).build()
    wheel_data.addSubscriber(WheelSubscriber())

    OrientationData.Builder(context, publisher_manager).build().addSubscriber(OrientationSubscriber())

    MicrophoneData.Builder(context, publisher_manager).build().addSubscriber(MicrophoneSubscriber())

    (ObjectDetectorData.Builder(context, publisher_manager, context)
     .setPreviewView(context.binding.cameraXPreview)
     .build()
     .addSubscriber(ObjectDetectorSubscriber()))

    QRCodeData.Builder(context, publisher_manager, context).build().addSubscriber(QRCodeSubscriber())

    publisher_manager.initializePublishers()
    publisher_manager.startPublishers()

    serial_manager = SerialCommManager(context.usbSerial,battery_data, wheel_data)
    context.setSerialCommManager(serial_manager)
    context.onSetupReady()

def loop():
    global speed, increment
    context.outputs.setWheelOutput(speed, speed, False, False)
    if speed >= 1.0 or speed <= -1.0:
        increment = -increment
    speed += increment

    context.guiUpdater.displayValues()
