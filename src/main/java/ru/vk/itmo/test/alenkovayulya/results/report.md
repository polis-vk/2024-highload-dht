MacBook-Pro-Ulia:wrk2-arm yulalenk$ ./wrk -c 1 -d 30 -t 1 -R 3000 -s /Users/yulalenk/sem2/2024-highload-dht/src/main/java/ru/vk/itmo/test/alenkovayulya/lua/put.lua http://localhost:8080
/Users/yulalenk/sem2/2024-highload-dht/src/main/java/ru/vk/itmo/test/alenkovayulya/lua/put.lua: .../src/main/java/ru/vk/itmo/test/alenkovayulya/lua/put.lua:10: 'end' expected (to close 'function' at line 3) near '<eof>'
/Users/yulalenk/sem2/2024-highload-dht/src/main/java/ru/vk/itmo/test/alenkovayulya/lua/put.lua: .../src/main/java/ru/vk/itmo/test/alenkovayulya/lua/put.lua:10: 'end' expected (to close 'function' at line 3) near '<eof>'
Running 30s test @ http://localhost:8080
1 threads and 1 connections
Thread calibration: mean lat.: 1.297ms, rate sampling interval: 10ms
Thread Stats   Avg      Stdev     Max   +/- Stdev
Latency     1.21ms    0.90ms  18.86ms   85.02%
Req/Sec     3.16k   406.40     8.00k    84.32%
89999 requests in 30.00s, 6.09MB read
Non-2xx or 3xx responses: 89999
Requests/sec:   2999.86
Transfer/sec:    208.00KB
