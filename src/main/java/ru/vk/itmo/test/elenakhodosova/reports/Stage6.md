# Этап 6. Range-запросы

C помощью wrk добавим записи в базу
```dtd
wrk2 -d 200 -t 1 -c 64 -R 2000 -L -s put.lua http://127.0.0.1:8080
```

Далее получим весь диапазон запросов с помощью curl
```
curl -v http://localhost:8080/v0/entities\?start\=1
```

[CPU profile](data/stage6/profile-get-range.html)

[Alloc profile](data/stage6/profile-range-alloc.html)

[Lock profile](data/stage6/profile-get-range-lock.html)