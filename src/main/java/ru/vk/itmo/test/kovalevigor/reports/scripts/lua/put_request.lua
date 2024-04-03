local lol = 0

function request()
     key = lol
     lol = lol + 1
     value = ""
     for i = 1, math.random(1, 100) do
        value = value .. math.random(1, 100000)
     end

     return wrk.format("PUT", "/v0/entity?id=k" .. key, {}, "v" .. value)
 end