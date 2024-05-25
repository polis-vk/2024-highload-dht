counter = 100000000

function request()
    counter = counter + 1
    headers = {}
    headers["Host"] = "localhost:8080"
    return wrk.format("GET", "/v0/entity?id=" .. tostring(counter), headers)

end