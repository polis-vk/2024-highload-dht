# Отчёт по 1 заданию.

Скрипты для теста методов лежат в папке [scripts](src/main/java/ru/vk/itmo/test/osipovdaniil/scripts) . 
Последовательные `put, get, delete`. <br>
`flushThresholdBytes = 2^20`<br>
Реализация `Dao` взята с референса.

## wrk2

Собственно сразу пошёл искать точку разладки запуская с помощью wrk2 скрипты 
[get](src/main/java/ru/vk/itmo/test/osipovdaniil/scripts/get.lua)
и [put](src/main/java/ru/vk/itmo/test/osipovdaniil/scripts/put.lua) .
с разным количеством запросов в секунду. <br>
Так я пришёл начиная с
[10000](ru/vk/itmo/test/osipovdaniil/report1/wrk/put:1)
к константе
[18500](ru/vk/itmo/test/osipovdaniil/report1/wrk/put:577)
запросов в секунду (между ними соответсвенно сам поиск и изменения задержек). `mean latency` было меньше секунды.
На тот момент было 80 SSTabl'ов.

Однако решив перетестировать, заметил увеличение таймингов. <br>
Так обнаружил несколько зависимостей:
1) Скорость работы как `put` так и `get` падает с увеличением количевства SSTable'ов.
[Последовательный put](ru/vk/itmo/test/osipovdaniil/report1/wrk/put:674)
2) `get` `delete` `get`. В такой последовательности второй `get` работает быстрее первого. <br>
Так же как 2-ой [delete](ru/vk/itmo/test/[delete](ru/vk/itmo/test/osipovdaniil/report1/wrk/delete:208)osipovdaniil/report1/wrk/delete:208) после
[первого](ru/vk/itmo/test/osipovdaniil/report1/wrk/delete:122). <br>
Логично ведь обновлённые данные лежат выше. Однако стоит отметить, что внутри все скрипты линейные.

Это было подтверждено, после сброса данных, вызовом скриптов в соотетсвующих последовательностях. <br>
Также было замечен стабильный
[экспоненциальный](src/main/java/ru/vk/itmo/test/osipovdaniil/report1/pictures/percentiles.html)
скачёк задержек на 75, 90 и выше персентилях. 
Как будто происходит накопление нагрузки.

## async-profiler

Результаты в папке
[asyncprof](src/main/java/ru/vk/itmo/test/osipovdaniil/report1/asyncprof)
<br>

С точки зрения cpu основные ресурсы съедает обработка запроса.
Однако есть концептуальная разница в распределнии, так
[get](src/main/java/ru/vk/itmo/test/osipovdaniil/report1/asyncprof/get cpu.html)
съедает больше значительно больше ресурсов за счёт поиска и возвращения результата.
В то время как
[put](src/main/java/ru/vk/itmo/test/osipovdaniil/report1/asyncprof/put alloc.html)
и [delete](src/main/java/ru/vk/itmo/test/osipovdaniil/report1/asyncprof/delete cpu.html)
сама обработка запроса съедает мало ресурсов, так как новые записи кладутся на верх. Однако появляется графа связанная
с вызвом метода `flush`.

С точки зрения аллокаций, распределение схожее, за исключением детали, что
в случае `put` и `delete` память аллоцируется на запись, 
а в случае `get` на возвращения результата 
