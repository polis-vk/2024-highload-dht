math.randomseed(os.time())

function request()
    key = math.random(100000000, 100000000 + 500000)
    headers = {}
    headers["Host"] = "localhost:8080"
    return wrk.format("GET", "/v0/entity?id=key" .. tostring(key), headers)
end