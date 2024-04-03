## Параметры системы:
* Treshhold bytes = 1 mb
* Количество core потоков = 6
* Максимальное количество потоков = 6
* Размер очереди = 400
* Max heap size = 128 mb
* Тестируем кластер из 4-х нод
* Параметр ack = 3, from = 4

##### Репликация реализована с последовательными запросами к каждой из нод кластера

## Сравнение с предыдущей реализацией

* Протестируем с параметрами wrk2 при тестировании системы без репликации из предыдущей лабораторной работы
  
  * GET (-c 250 -t 1 -R 5000)
    ![](./screens/img_hist/get_new_old.png)
  
  * PUT (-c 250 -t 1 -R 4500)
    ![](./screens/img_hist/put_new_old.png)

## Определим рабочую нагрузку

  * GET
    ![](./screens/img_hist/get.png)
    ![](./screens/img_hist/get_solo.png)

  * PUT
    ![](./screens/img_hist/put.png)
    ![](./screens/img_hist/put_solo.png)

## Проведем профилирование

  * GET
    * ALLOC
      ![](./screens/get/alloc_1.png)
      ![](./screens/get/alloc_2.png)
    * CPU
      ![](./screens/get/cpu_1.png)
      ![](./screens/get/cpu_2.png)
    * LOCK
      ![](./screens/get/lock_1.png)
      ![](./screens/get/lock_2.png)
      ![](./screens/get/lock_3.png)

  * PUT
    * ALLOC
      ![](./screens/put/alloc_1.png)
      ![](./screens/put/alloc_2.png)
    * CPU
      ![](./screens/put/cpu_1.png)
      ![](./screens/put/cpu_2.png)
    * LOCK
      ![](./screens/put/lock_1.png)
      ![](./screens/put/lock_2.png)
