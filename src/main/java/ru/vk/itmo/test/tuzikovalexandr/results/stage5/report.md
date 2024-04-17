## Начальные сведения
* Виртуалке были даны 4 ядра (будем надеяться, что не помрет)
* Конфигурация кластера: 3 ноды
* Реплицирование по умолчанию (2/3)

## PUT 5000 30s 1 thread 2 con

Работающие входные данные остаются с прошлого этапа. Результаты эксперимента в сравнении с прошлым этапом при
1 поток, 2 соединение, 5000 запросов приведены ниже. [put-profile-1th-2con-5000.txt](put-profile-1th-2con-5000.txt)

```
            было        стало
 50.000%    5.12s       17.25s
 75.000%    7.24s       21.48s
 90.000%    7.24s       23.95s
 99.000%    8.55s       25.41s
 99.900%    9.33s       25.53s
 99.990%    9.33s       25.53s
 99.999%    9.33s       25.54s
100.000%    9.33s       25.54s
```

## CPU

[put-profile-1th-cpu.html](put-profile-1th-cpu.html)

## ALLOC

[put-profile-1th-alloc.html](put-profile-1th-alloc.html)

## LOCK

[put-profile-1th-lock.html](put-profile-1th-lock.html)

## GET 5000 30s 1 thread 2 con

```
            было        стало
 50.000%   19.96s       14.93s
 75.000%   24.76s       17.96s
 90.000%   27.25s       23.90s
 99.000%   28.48s       26.10s
 99.900%   28.56s       26.33s
 99.990%   28.57s       26.35s
 99.999%   28.57s       26.35s
100.000%   28.57s       26.35s
```

## CPU

[get-profile-1th-cpu.html](get-profile-1th-cpu.html)

## ALLOC

[get-profile-1th-alloc.html](get-profile-1th-alloc.html)

## LOCK

[get-profile-1th-lock.html](get-profile-1th-lock.html)

## Выводы

## Улучшения

P.S. Снова спасибо большое виртуалке, что за время написания отчета и проведения экспериментов она ниразу не умерла.