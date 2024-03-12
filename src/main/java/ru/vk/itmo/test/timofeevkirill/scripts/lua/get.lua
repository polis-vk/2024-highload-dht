math.randomseed(os.time())

function request()
    counter = math.random(100000100, 100000000 + 5000000)
    headers = {}
    headers["Host"] = "localhost:8080"
    return wrk.format("GET", "/v0/entity?id=" .. tostring(counter), headers)

end