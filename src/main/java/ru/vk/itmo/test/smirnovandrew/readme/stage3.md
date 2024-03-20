```dtd
./wrk -d 120 -t 1 -c 64 -R 30000 -L -s /home/andrew/my-dht/2024-highload-dht/src/main/java/ru/vk/itmo/test/smirnovandrew/lua/put.lua http://localhost:8080
        sudo ./profiler.sh --fdtransfer -d 120 -e cpu -f put_cpu_stage3.html 3791


```