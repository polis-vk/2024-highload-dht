---
--- Created by vitekkor.
--- DateTime: 17.02.2024 19:06
---

host = "localhost"
port = 8080

counter = 0

request = function()
    path = "/v0/entity?id=key" .. counter
    method = "PUT"
    math.randomseed(counter)
    body = "value" .. counter
    counter = counter + 1
    return wrk.format(method, path, nil, body)
end
