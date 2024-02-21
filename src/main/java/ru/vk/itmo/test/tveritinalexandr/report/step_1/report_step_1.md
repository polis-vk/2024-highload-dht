## PUT
Для генерации запросов использовался wrk2 и скрипт [put_request.lua](src/main/java/ru/vk/itmo/test/tveritinalexandr/lua/put_request.lua). 
Параметры 1 запуска ```./wrk -d 30 -t 1 -c 1 -R 100 -L -s``` - результаты представлены в файле
put_wrk_2.png. Держит незначительную нагрузку в 100 rps. При повышении rps хотя бы до 150 (put_max_wrk2.png), мы аномально
упираемся в значение 3274 requests и далее получаем ошибки ```22:18:03.067 [NIO Selector #3] DEBUG one.nio.net.Session -- Connection closed: 0:0:0:0:0:0:0:1```
(с этим предстоит разобраться)
## GET
Для генерации запросов использовался wrk2 и скрипт [get_request.lua](src/main/java/ru/vk/itmo/test/tveritinalexandr/lua/get_request.lua).
Параметры 1 запуска ```./wrk -d 30 -t 1 -c 1 -R 20000 -L -s``` - результаты представлены в файле (get_wrk2). При дальнейшем увеличении rps
начинается деградация по latency (get_max_wrk2)

