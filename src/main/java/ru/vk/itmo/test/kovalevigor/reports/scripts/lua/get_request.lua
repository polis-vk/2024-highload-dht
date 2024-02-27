function request()
     key = math.random(1000000)

     return wrk.format("GET", "/v0/entity?id=k" .. key)
 end
