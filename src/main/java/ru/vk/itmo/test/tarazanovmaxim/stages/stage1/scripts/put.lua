wrk.method = "PUT"

request = function()
    path = "/v0/entity?id=1"
    body = "hello world!"
    headers = {}
    headers["Content-Type"] = "text/plain"
    return wrk.format(nil, path, headers, body)
end