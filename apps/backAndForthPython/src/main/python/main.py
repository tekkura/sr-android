speed = 0.0
increment = 0.4

def setup():
    print("Hello World!")

def loop():
    global speed, increment
    outputs.setWheelOutput(speed, speed, False, False)

    if speed >= 1.0 or speed <= -1.0:
        increment = -increment
    speed += increment