counter = 0

function request()
    path = "/v0/entity?id=" .. counter .. "&ack=2&from=3"
    wrk.method = "GET"
    counter = counter + 1
    return wrk.format(nil, path)
end
