# Этап 6. Range-запросы

C помощью wrk добавим записи в базу
```dtd
wrk2 -d 300 -t 1 -c 64 -R 4000 -L -s put.lua http://127.0.0.1:8080
----------------------------------------------------------
1199995 requests in 5.00m, 76.68MB read
Requests/sec:   3999.98
Transfer/sec:    261.72KB
```

Далее получим весь диапазон запросов с помощью curl
```
curl -v http://localhost:8080/v0/entities\?start\=1
```

[CPU profile](data/stage6/profile-get-range.html)

От всего времени выполнения запроса работа с dao занимает меньше процента, больше половины сэмплов уходит на Session.write()
Примерно 1/6 от запроса - работа с итератором

[Alloc profile](data/stage6/profile-range-alloc.html)

На профиле аллокаций всего 16% сэмплов приходится на итератор, еще примерно 20% - выделения памяти при обработке чанка (toArray и toHexString),
все остальное - работа one.nio

[Lock profile](data/stage6/profile-range-lock.html)

По профилю локов видно, что все блокировки происходят во время работы с сессией