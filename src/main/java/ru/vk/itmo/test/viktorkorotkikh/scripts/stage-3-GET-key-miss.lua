---
--- Created by vitekkor.
--- DateTime: 17.03.2024 21:16
---

host = "localhost"
port = 8080

math.randomseed(os.clock())

request = function()
    rnd = math.random(2000000, 3500000)
    path = "/v0/entity?id=key" .. rnd
    method = "GET"
    return wrk.format(method, path)
end
