function request()
    headers = { }
    headers["Host"] = "localhost:8080"
    key = "key=" .. math.random(1000000, 3671869)
    local valueAsBody = "value=" .. (tostring({}):sub(math.random(25, 50)))
    return wrk.format("GET", "/v0/entity?id=" .. key, headers, valueAsBody)
end