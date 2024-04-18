## Параметры системы:
* Treshhold bytes = 1 mb
* Количество core потоков = 6
* Максимальное количество потоков = 6
* Размер очереди = 400
* Max heap size = 128 mb
* Тестируем кластер из 4-х нод
* Параметр ack = 3, from = 4


## Сравнение с предыдущей реализацией

* GET (-c 250 -t 1 -R 1100)
  ![](./screens/get_cmp.png)

* PUT (-c 250 -t 1 -R 750)
  ![](./screens/put_cmp.png)


## Определим рабочую нагрузку

* GET
  ![](./screens/get.png)

* PUT
  ![](./screens/put.png)


## Проведем профилирование

* GET
    * ALLOC
      ![](./screens/img.png)
      ![](./screens/img_1.png)
    * CPU
      ![](./screens/img_2.png)
      ![](./screens/img_3.png)
    * LOCK
      ![](./screens/img_4.png)
      ![](./screens/img_5.png)
  
* PUT
    * ALLOC
      ![](./screens/img_6.png)
      ![](./screens/img_7.png)
    * CPU
      ![](./screens/img_8.png)
      ![](./screens/img_9.png)
    * LOCK
      ![](./screens/img_10.png)
      ![](./screens/img_11.png)


### Итого