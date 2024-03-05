wrk -t 1 -c 1 -d 60 -R 2000 -s ../lua/put_sequence.lua -L http://0.0.0.0:8080/ | tee wrk_put.txt
wrk -t 1 -c 1 -d 60 -R 2000 -s ../lua/get_sequence.lua -L http://0.0.0.0:8080/ | tee wrk_get.txt

