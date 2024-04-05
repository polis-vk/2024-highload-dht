---
--- Created by vitekkor.
--- DateTime: 17.02.2024 19:06
---

host = "localhost"
port = 8080

math.randomseed(os.clock()) --- 1709820295

request = function()
    rnd = math.random(0, 3000000)
    path = "/v0/entity?id=key" .. rnd
    method = "PUT"
    body = "value" .. rnd
    return wrk.format(method, path, nil, body)
end
