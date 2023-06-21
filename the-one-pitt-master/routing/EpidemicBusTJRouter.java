/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;

/**
 *
 * @author Kalis
 */
public class EpidemicBusTJRouter implements RoutingDecisionEngineBusTJ{

    public EpidemicBusTJRouter(Settings s) {
        super();
    }

    public EpidemicBusTJRouter(EpidemicBusTJRouter proto) {
        super();
    }

    @Override
    public void connectionUp(DTNHost thisHost, DTNHost peer) {
    }

    @Override
    public void connectionDown(DTNHost thisHost, DTNHost peer) {
    }

    @Override
    public void doExchangeForNewConnection(Connection con, DTNHost peer) {
    }

    @Override
    public boolean newMessage(Message m) {
        return true;
    }

    @Override
    public boolean isFinalDest(Message m, DTNHost aHost) {
        return m.getTo() == aHost;
    }

    @Override
    public boolean shouldSaveReceivedMessage(Message m, DTNHost thisHost) {
        if (String.valueOf(thisHost.toString().charAt(0)).equals("s")) {
            return false;
        }
        return m.getTo()!=thisHost;
    }

    @Override
    public boolean shouldSendMessageToHost(Message m, DTNHost otherHost, DTNHost thisHost) {
        if (String.valueOf(otherHost.toString().charAt(0)).equals("s")) {
            return false;
        }
        return true;
    }

    @Override
    public boolean shouldDeleteSentMessage(Message m, DTNHost otherHost, DTNHost thisHost) {
        if (String.valueOf(thisHost.toString().charAt(0)).equals("s")) {
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldDeleteOldMessage(Message m, DTNHost hostReportingOld) {
        return false;
    }

    @Override
    public RoutingDecisionEngineBusTJ replicate() {
        return new EpidemicBusTJRouter(this);
    }

    private EpidemicBusTJRouter getOtherEpidemicRouter(DTNHost host) {
        MessageRouter otherRouter = host.getRouter();
        assert otherRouter instanceof DecisionEngineRapidRouter : "This router only works "
                + " with other routers of same type";

        return (EpidemicBusTJRouter) ((DecisionEngineRapidRouter) otherRouter).getDecisionEngine();
    }
}
