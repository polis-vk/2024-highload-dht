counter = 0

function request()
    wrk.port = "8080"
    counter = counter + 1
    return wrk.format("PUT", "/v0/entity?id=key" .. counter, {}, "manul" .. counter)
end