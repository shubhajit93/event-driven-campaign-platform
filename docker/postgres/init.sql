-- One database per service: each service owns its data.
create user campaigns with password 'campaigns';
create database campaigns owner campaigns;
create user deliveries with password 'deliveries';
create database deliveries owner deliveries;
