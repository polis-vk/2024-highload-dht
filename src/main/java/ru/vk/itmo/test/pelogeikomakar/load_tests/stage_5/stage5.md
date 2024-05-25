# stage 5

## PUT

```shell
wrk -d 30 -t 1 -c 64 -R 5000 -L -s ./lua/put_req.lua http://127.0.0.1:8080
```
При 5000 RPS, 64 подключениях получились следующие результаты:

```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.42ms
 75.000%    1.98ms
 90.000%    2.49ms
 99.000%    5.20ms
 99.900%    8.07ms
 99.990%   10.38ms
 99.999%   10.95ms
100.000%   12.20ms
-----------------------
Requests/sec:   4974.31
```

Была расширена очередь для пулов, поскольку при тестировании появлялись ошибки `RejectedExecutionException`.

При RPS 5000 и изменении количесва подключений 1 -> 10 -> 20 -> 40 -> 80 -> 120 
получены реузльтаты:

```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 cons            1 ->      10 ->      20 ->      40 ->      80 ->     120 
 50.000%    1.08ms ->  1.34ms ->  1.45ms ->  1.43ms ->  1.43ms ->  1.41ms
 75.000%    1.89ms ->  1.91ms ->  2.01ms ->  1.98ms ->  2.01ms ->  1.96ms
 90.000%    4.95ms ->  2.40ms ->  2.58ms ->  2.54ms ->  2.59ms ->  2.56ms
 99.000%   15.66ms ->  5.39ms ->  5.45ms ->  5.20ms ->  5.29ms ->  5.20ms
 99.900%   17.87ms ->  7.94ms ->  7.70ms ->  7.78ms ->  7.70ms ->  7.68ms
 99.990%   18.37ms -> 10.52ms ->  8.70ms ->  9.02ms ->  9.36ms ->  9.80ms
 99.999%   18.42ms -> 12.41ms ->  9.62ms ->  9.65ms -> 10.31ms -> 10.53ms
100.000%   18.42ms -> 12.41ms ->  9.62ms ->  9.65ms -> 10.31ms -> 10.53ms
-------------------------------------------------------------------------
RPS:       4997.15 -> 4988.35 -> 4976.17 -> 4952.39 -> 4904.31 -> 4856.38
```

Видно, что фактический RPS падает, при этом latency (100 перцентиль) сначала уменьшается, а 
потом постепенно увеличивается. Предположу что есть влияние самого wrk и то, что все-таки 
подключения пересоздаются, что увеличивает нагрузку.


###  async-profiler

```shell
STAGE=5
METHOD_NAME=PUT
TITLE_PREF=${METHOD_NAME}-t1c64rps-5000
DATA_DIR="/media/user/DATA/projects/gitproj/DWS-ITMO-2023/sem_2/2024-highload-dht/src/main/java/ru/vk/itmo/test/pelogeikomakar/load_tests/stage_${STAGE}/data"
JFR_FILENAME="profile${STAGE}-${TITLE_PREF}.jfr"


./bin/asprof --title ${TITLE_PREF} -e cpu,alloc,lock -d 30 -f ${DATA_DIR}/${JFR_FILENAME} MainServ

java -cp lib/converter.jar jfr2flame --alloc --title ${TITLE_PREF}-ALLOC ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-alloc.html"
java -cp lib/converter.jar jfr2flame --lock --title ${TITLE_PREF}-LOCK ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-lock.html"
java -cp lib/converter.jar jfr2flame --title ${TITLE_PREF}-CPU ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-cpu.html"
```

### Flame graphs
[CPU-PUT flame graph](data/profile5-PUT-t1c64rps-5000-cpu.html)

[Allocation-PUT flame graph](data/profile5-PUT-t1c64rps-5000-alloc.html)

[Lock-PUT flame graph](data/profile5-PUT-t1c64rps-5000-lock.html)

На flame graph видно, что аллокаций связанных с отправкой запроса на реплику стало меньше
по сравнению с `stage4` (~45% против ~55%), но суммарно аллокаций, связанных 
с `http.client` стало больше. Это связано с 
переходом на `java.net.http.HttpClient`.

Профиль локов изменился по сравнению со `stage4`: раньше были только локи сервера
и пула потоков (точнее очереди), теперь добавились локи `java.net.http.HttpClient` 
для получения ответа из send/sendAsync и внутренние локи `java.net.http.HttpClient`

Большая часть CPU сэмплов отводится обработке запроса и http клиенту и серверу,
на работу с Dao потрачено ~2% сэмплов.

  <br>

---------------------------------------------------
<br>

## GET

Данные
```shell
wrk -d 38 -t 1 -c 64 -R 10000 -L -s ./lua/put_req.lua http://127.0.0.1:8080
```
и
```shell
wrk -d 160 -t 1 -c 64 -R 10000 -L -s ./lua/put_req.lua http://127.0.0.1:8080
```
<br>

При наличии ~371 000 уникальных ключей RPS 6000 и 64 подключениях получились следующие результаты:
```shell
wrk -d 10 -t 1 -c 64 -R 6000 -L -s ./lua/get_random_req.lua http://127.0.0.1:8080
```

```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.45ms
 75.000%    1.98ms
 90.000%    2.46ms
 99.000%    3.29ms
 99.900%    4.31ms
 99.990%    5.35ms
 99.999%    5.64ms
100.000%    6.05ms
------------------
Requests/sec:   5907.26
```

При наличии ~1 600 000 уникальных ключей RPS 6000 и 64 подключениях получились следующие результаты:
```shell
wrk -d 10 -t 1 -c 64 -R 6000 -L -s ./lua/get_random_req.lua http://127.0.0.1:8080
```

```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.66ms
 75.000%    2.29ms
 90.000%    2.88ms
 99.000%    4.21ms
 99.900%    5.97ms
 99.990%    6.82ms
 99.999%    7.36ms
100.000%    7.53ms
------------------
Requests/sec:   5906.82
```


###  async-profiler

При количестве ключей: ~371 000
```shell
STAGE=5
METHOD_NAME=GET
TITLE_PREF=${METHOD_NAME}-t1c64rps-6000
DATA_DIR="/media/user/DATA/projects/gitproj/DWS-ITMO-2023/sem_2/2024-highload-dht/src/main/java/ru/vk/itmo/test/pelogeikomakar/load_tests/stage_${STAGE}/data"
JFR_FILENAME="profile${STAGE}-${TITLE_PREF}.jfr"


./bin/asprof --title ${TITLE_PREF} -e cpu,alloc,lock -d 30 -f ${DATA_DIR}/${JFR_FILENAME} MainServ

java -cp lib/converter.jar jfr2flame --alloc --title ${TITLE_PREF}-ALLOC ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-alloc.html"
java -cp lib/converter.jar jfr2flame --lock --title ${TITLE_PREF}-LOCK ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-lock.html"
java -cp lib/converter.jar jfr2flame --title ${TITLE_PREF}-CPU ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-cpu.html"
```

При количестве ключей: ~1 600 000
```shell
STAGE=5
METHOD_NAME=GET
TITLE_PREF=${METHOD_NAME}-t1c64rps-6100
DATA_DIR="/media/user/DATA/projects/gitproj/DWS-ITMO-2023/sem_2/2024-highload-dht/src/main/java/ru/vk/itmo/test/pelogeikomakar/load_tests/stage_${STAGE}/data"
JFR_FILENAME="profile${STAGE}-${TITLE_PREF}.jfr"


./bin/asprof --title ${TITLE_PREF} -e cpu,alloc,lock -d 30 -f ${DATA_DIR}/${JFR_FILENAME} MainServ

java -cp lib/converter.jar jfr2flame --alloc --title ${TITLE_PREF}-ALLOC ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-alloc.html"
java -cp lib/converter.jar jfr2flame --lock --title ${TITLE_PREF}-LOCK ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-lock.html"
java -cp lib/converter.jar jfr2flame --title ${TITLE_PREF}-CPU ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-cpu.html"
```

### Flame graphs

[CPU-GET flame graph 371k](data/profile5-GET-t1c64rps-6000-cpu.html)

[Allocation-GET flame graph 371k](data/profile5-GET-t1c64rps-6000-alloc.html)

[Lock-GET flame graph 371k](data/profile5-GET-t1c64rps-6000-lock.html)

[CPU-GET flame graph 1600k](data/profile5-GET-t1c64rps-6100-cpu.html)

[Allocation-GET flame graph 1600k](data/profile5-GET-t1c64rps-6100-alloc.html)

[Lock-GET flame graph 1600k](data/profile5-GET-t1c64rps-6100-lock.html)

<br>

При увеличении количесва уникальных ключей количество сэмплов CPU на 
поиск ключа в локальном хранилище вырасло с ~7% до ~18%. Большая часть CPU теперь 
тратиться на отправку запросов между репликами по сравнению с предыдущим этапом. Это связано
с переходом на `java.net.http.HttpClient`.

Практически все аналогично тому, что видно при `PUT` запросах, кроме того, 
что работа с Dao занимет больше сэмплов в процентном соотношении.

---------------------------------------------------
<br>

## Заключение

- Точка разладки вправо (увеличилась) для GET и PUT запросов причем очень сильно (~5000 RPS). 
Это связано с распараллеливанием запросов и отправкой ответа при наличии кворума 
(то есть при выполнении 2х запросов из 3х)
- Разница в RPS между `GET` и `PUT` запросами сократилась. Это связано с тем, что
большая часть работы уходит на http клиент/сервер и обработку самих запросов