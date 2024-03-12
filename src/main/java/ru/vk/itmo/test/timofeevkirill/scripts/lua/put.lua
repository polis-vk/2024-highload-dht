counter = 100000000

function request()
    counter = counter + 1
    body = tostring(counter) .. tostring(counter) .. tostring(counter)
    headers = {}
    headers["Content-Type"] = "text/plain"
    headers["Content-Length"] = #{string.byte(body, 1, -1)}
    headers["Host"] = "localhost:8080"
    return wrk.format("PUT", "/v0/entity?id=" .. tostring(counter), headers, body)

end