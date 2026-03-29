FROM ros:kilted-ros-base

RUN apt-get update && apt-get install -y \
    ros-${ROS_DISTRO}-rosbridge-suite \
    && rm -rf /var/lib/apt/lists/*

CMD ["bash", "-lc", "source /opt/ros/$ROS_DISTRO/setup.bash && ros2 launch rosbridge_server rosbridge_websocket_launch.xml"]
