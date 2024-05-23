# stage 4

## PUT

По неизвестной причине wrk2 Выдавал следующий результат: 

```text
Running 10s test @ http://127.0.0.1:8080
  1 threads and 1 connections
  Thread calibration: mean lat.: 9223372036854776.000ms, rate sampling interval: 10ms
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     -nanus    -nanus   0.00us    0.00%
    Req/Sec       -nan      -nan   0.00      0.00%
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    0.00us
 75.000%    0.00us
 90.000%    0.00us
 99.000%    0.00us
 99.900%    0.00us
 99.990%    0.00us
 99.999%    0.00us
100.000%    0.00us

  Detailed Percentile spectrum:
       Value   Percentile   TotalCount 1/(1-Percentile)

       0.000     1.000000            0          inf
#[Mean    =         -nan, StdDeviation   =         -nan]
#[Max     =        0.000, Total count    =            0]
#[Buckets =           27, SubBuckets     =         2048]
----------------------------------------------------------
  0 requests in 10.00s, 0.00B read
  Socket errors: connect 0, read 92848, write 0, timeout 0
Requests/sec:      0.00
Transfer/sec:       0.00B
```
```shell
wrk -d 10 -t 1 -c 1 -R 1 -L -s ./lua/put_req.lua http://127.0.0.1:8080
```

В GitHab [wrk2](https://github.com/giltene/wrk2) сказано, что стандартное значение
R (requests per second) это 1000. Я не нашел информации о том, что нельзя задавать 
параметр R = 1 (меньше 1000). При попытке запуска wrk2 с значениями `-d 10 -t 1 -c 1 -R 1 ` 
ожидается ~10 запросов за 10 секунд, но судя по логам и информации в базе, было совершено более 8 000 запросов. 

###  async-profiler

```shell
STAGE=4
METHOD_NAME=PUT
TITLE_PREF=${METHOD_NAME}-t1c10rps-1000
DATA_DIR="/media/user/DATA/projects/gitproj/DWS-ITMO-2023/sem_2/2024-highload-dht/src/main/java/ru/vk/itmo/test/pelogeikomakar/load_tests/stage_${STAGE}/data"
JFR_FILENAME="profile${STAGE}-${TITLE_PREF}.jfr"


./bin/asprof --title ${TITLE_PREF} -e cpu,alloc,lock -d 30 -f ${DATA_DIR}/${JFR_FILENAME} MainServ

java -cp lib/converter.jar jfr2flame --alloc --title ${TITLE_PREF}-ALLOC ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-alloc.html"
java -cp lib/converter.jar jfr2flame --lock --title ${TITLE_PREF}-LOCK ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-lock.html"
java -cp lib/converter.jar jfr2flame --title ${TITLE_PREF}-CPU ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-cpu.html"
```

### Flame graphs
[CPU-PUT flame graph](data/profile4-PUT-t1c10rps-1000-cpu.html)

[Allocation-PUT flame graph](data/profile4-PUT-t1c10rps-1000-alloc.html)

[Lock-PUT flame graph](data/profile4-PUT-t1c10rps-1000-lock.html)


  <br>

---------------------------------------------------
<br>

## GET

Данные
```shell
wrk -d 40 -t 1 -c 120 -R 10000 -L -s ./lua/put_req.lua http://127.0.0.1:8080
```

Запросы
```shell
wrk -d 40 -t 1 -c 120 -R 1000 -L -s ./lua/get_random_req.lua http://127.0.0.1:8080
```

```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%   14.25s 
 75.000%   25.10s 
 90.000%   25.10s 
 99.000%   25.10s 
 99.900%   25.10s 
 99.990%   25.10s 
 99.999%   25.10s 
100.000%   25.10s 
```

При изменении количества подключений 1 -> 10 -> 50 -> 100 -> 120 четких изменений выявлено не было.
Видно, что сервер не справляется
###  async-profiler

```shell
STAGE=4
METHOD_NAME=GET
TITLE_PREF=${METHOD_NAME}-t1c10rps-1000
DATA_DIR="/media/user/DATA/projects/gitproj/DWS-ITMO-2023/sem_2/2024-highload-dht/src/main/java/ru/vk/itmo/test/pelogeikomakar/load_tests/stage_${STAGE}/data"
JFR_FILENAME="profile${STAGE}-${TITLE_PREF}.jfr"


./bin/asprof --title ${TITLE_PREF} -e cpu,alloc,lock -d 30 -f ${DATA_DIR}/${JFR_FILENAME} MainServ

java -cp lib/converter.jar jfr2flame --alloc --title ${TITLE_PREF}-ALLOC ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-alloc.html"
java -cp lib/converter.jar jfr2flame --lock --title ${TITLE_PREF}-LOCK ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-lock.html"
java -cp lib/converter.jar jfr2flame --title ${TITLE_PREF}-CPU ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-cpu.html"
```

### Flame graphs

[CPU-GET flame graph](data/profile4-GET-t1c10rps-1000-cpu.html)

[Allocation-GET flame graph](data/profile4-GET-t1c10rps-1000-alloc.html)

[Lock-GET flame graph](data/profile4-GET-t1c10rps-1000-lock.html)

<br>

---------------------------------------------------
<br>

## Вывод

- Точка разладки сместилась влево (уменьшилась) для GET и PUT запросов причем очень сильно (RPS менее 1000). 
Появилось 3 дополнительных последовательных запроса (ack = 2, from = 3), которые требуют "дорогие" 
операции записи в сокет и "дорогие" операции поиска (для GET запросов). Возможно, если распараллелить запросы
на реплики, то точка разладки сместиться вправо (увеличится).
- То, что wrk "не может" выдавать меньше 1000 RPS оказалось удивительным открытием.
- Думал, что добавление времени (long) к value в Entry будет сильно негативано сказываться, но, 
исходя из flame grahs, видно, что на преобразование тратиться мало ресурсов 
(меньше 0.5% аллокаций и меньше 0.5% семплов CPU).