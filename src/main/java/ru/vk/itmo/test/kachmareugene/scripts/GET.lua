counter = 0
-- wrk -d 10 -t 1 -c 1 -R 10000 -L http://localhost:8080/v0/entity -s scripts/GET.lua

function request()
   wrk.port = "8080"
   counter = counter + 1
   return wrk.format("GET", "/v0/entity?id=key" .. counter, {})
end