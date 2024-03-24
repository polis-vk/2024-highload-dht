counter = 0

function request()
    path = "/v0/entity?id=" .. counter
    wrk.method = "GET"
    counter = counter + 1
    return wrk.format(nil, path)
end
