counter = 0
request = function()
    path = "/v0/entity?id=" .. counter
    wrk.method = "PUT"
    wrk.headers["X-Counter"] = counter
    wrk.body = counter
    counter = counter + 1
    return wrk.format(nil, path)
end