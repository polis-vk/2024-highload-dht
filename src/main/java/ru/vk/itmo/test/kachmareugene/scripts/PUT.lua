counter = 0

function request()
    wrk.port = "8080"
    counter = counter + 1
    return wrk.format("PUT", "/v0/entity?id=key" .. counter, {}, "manul" .. counter)
end

--
-- response = function(status, headers, body)
--   if status ~= 200 then
--     io.write("Status: ".. status .."\n")
--     io.write("Body:\n")
--     io.write(body .. "\n")
--   end
-- end