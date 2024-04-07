---
--- Created by vitekkor.
--- DateTime: 17.02.2024 19:06
---

host = "localhost"
port = 8080

math.randomseed(os.clock()) --- 1709820295

request = function()
    rnd = math.random(750000, 2250000)
    path = "/v0/entity?id=key" .. rnd
    method = "GET"
    return wrk.format(method, path)
end
