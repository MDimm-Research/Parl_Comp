#!/bin/sh

#export JAVA_HOME=/usr/lib/jvm/java-1.7.0-openjdk.x86_64
if [ -z "${JAVA_HOME}" ]
then
    echo "Please set environment JAVA_HOME";
    exit 1
fi

module="$1"
port="$2"
usage() {
    echo "Usage: `basename $0` [tran*|sess*|topi*|busi*]"
    exit 1
}

cluster_system=SocketIOSystem
cluster_seed=127.0.0.1:2551
cluster_host=127.0.0.1

case $module in
    sess*)   cluster_module="session";   akka_args="-Dakka.cluster.seed-nodes.0=akka.tcp://${cluster_system}@${cluster_seed}";;
    topi*)   cluster_module="topic";     akka_args="-Dakka.cluster.seed-nodes.0=akka.tcp://${cluster_system}@${cluster_seed}";;
    tran*)   cluster_module="transport"; akka_args="-Dakka.cluster.seed-nodes.0=akka.tcp://${cluster_system}@${cluster_seed}";;
    busi*)   cluster_module="business";  akka_args="-Dspray.socketio.cluster.client-initial-contacts.0=akka.tcp://${cluster_system}@${cluster_seed}/user/receptionist";;
    *) usage
esac

benchserver_conf=../conf/cluster.conf
benchserver_class_pgm=spray.contrib.socketio.examples.benchmark.SocketIOTestClusterServer
benchserver_id_pgm=bench${cluster_module}
benchserver_lock_file=.lock_bench${cluster_module}
logback_conf=../conf/logback_${cluster_module}.xml

export JAVA=${JAVA_HOME}/bin/java
export FLAGS="-server -Dfile.encoding=UTF8 -XX:+UseNUMA -XX:+UseCondCardMark -XX:-UseBiasedLocking"
export HEAP="-Xms1024M -Xmx10240M -Xss1M"
export GC="-XX:+UseParallelGC"
export DB="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8888"

cp="";
for f in ../lib/*.jar;
do cp=${f}":"${cp};
done;

#2> /dev/null > /dev/tcp/127.0.0.1/2551
if [ "$port" == "" ]; then
    cluster_port=0
else
    #2551
    cluster_port=$port 
fi

$JAVA $FLAGS $HEAP $GC -Dconfig.file=${benchserver_conf} -Dlogback.configurationFile=${logback_conf} ${akka_args} -Dakka.remote.netty.tcp.hostname=${cluster_host} -Dakka.remote.netty.tcp.port=${cluster_port} -cp ${cp} ${benchserver_class_pgm} ${cluster_module} > ../logs/rt_${module}.log &
benchserver_pid=$!
echo $benchserver_pid > ./${benchserver_lock_file}
echo "Started $module, pid is $benchserver_pid, port is $cluster_port"
