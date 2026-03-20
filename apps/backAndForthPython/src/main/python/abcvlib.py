import time
import builtins
import main

outputs = None
loop_delay = 0.5


def run():
    builtins.outputs = outputs

    main.setup()
    print("setup done")

    print("start main-loop with delay", loop_delay)
    while True:
        main.loop()
        time.sleep(loop_delay)
