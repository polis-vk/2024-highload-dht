package ru.vk.itmo.test.andreycheshev;

public class ParametersParser {
    private final RendezvousDistributor distributor;
    private int ack;
    private int from;

    public ParametersParser(RendezvousDistributor distributor) {
        this.distributor = distributor;
    }

    public void parseAckFrom(String ackParameter, String fromParameter) throws IllegalArgumentException {
        if (ackParameter != null && fromParameter != null) {
            try {
                ack = Integer.parseInt(ackParameter);
                from = Integer.parseInt(fromParameter);
            } catch (Exception e) {
                setDefault();
                return;
            }
            if (ack <= 0 || ack > from) {
                throw new IllegalArgumentException();
            }
        } else {
            setDefault();
        }
    }

    private void setDefault() {
        ack = distributor.getQuorumNumber();
        from = distributor.getNodeCount();
    }

    public int getAck() {
        return ack;
    }

    public int getFrom() {
        return from;
    }
}
