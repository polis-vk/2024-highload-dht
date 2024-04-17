---
--- Created by vitekkor.
--- DateTime: 13.04.2024 19:06
---

host = "localhost"
port = 8080

math.randomseed(os.clock()) --- 1709820295

putRequest = function()
    rnd = math.random(0, 3000000)
    path = "/v0/entity?id=key" .. rnd
    method = "PUT"
    body = "value" .. rnd
    return wrk.format(method, path, nil, body)
end

getRequest = function()
    rnd = math.random(750000, 2250000)
    path = "/v0/entity?id=key" .. rnd
    method = "GET"
    return wrk.format(method, path)
end

requests = {}
requests[0] = getRequest
requests[1] = putRequest

request = function()
    return requests[math.random(0, 1)]()
end
