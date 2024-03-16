# Отчёт по 2 стэйджу

## wrk

Стоит отметить небольшое ухудшение работы при 1 коннекшене при добавлении `ExecutorService` по сравнении с его отсутсвием. <br>
В тоже время при 64 и 128 коннекшенах наблюдается стабильная работа с маленькими задержками.
![percentiles](/home/sbread/Desktop/labs/HLS/2024-highload-dht/src/main/java/ru/vk/itmo/test/osipovdaniil/report2/graphics/percentiles.png)

[detailed wrk 64 connections][1]. <br>
[detailed wrk 128 connections][2].

[1]: /home/sbread/Desktop/labs/HLS/2024-highload-dht/src/main/java/ru/vk/itmo/test/osipovdaniil/report2/wrk/wrk:78
[2]: /home/sbread/Desktop/labs/HLS/2024-highload-dht/src/main/java/ru/vk/itmo/test/osipovdaniil/report2/wrk/wrk:453

## asyncprof

[detailed profiling in this folder][3]

[3]: /home/sbread/Desktop/labs/HLS/2024-highload-dht/src/main/java/ru/vk/itmo/test/osipovdaniil/report2/asyncprof

Основные тенденции не меняются, однако появляются дополнительные расходы как в cpu, так и аллокациях (примерно 10%), 
связанных с `ExecutorService`.

### lock

Распределение lock'ов везде схожее и почти не меняется при разнов количестве коннекшенов.
Так примерно 25-30% времени это lock на очередь, 60% lock на ожидание завершения таски, 5-10% на обработку запроса, а оставшееся уходит на `SelectorThread`

